/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley;

import android.os.Process;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing cache triage on a queue of requests.
 *
 * Requests added to the specified cache queue are resolved from cache.
 * Any deliverable response is posted back to the caller via a
 * {@link ResponseDelivery}.  Cache misses and responses that require
 * refresh are enqueued on the specified network queue for processing
 * by a {@link NetworkDispatcher}.
 */
public class CacheDispatcher extends Thread {

    private static final boolean DEBUG = VolleyLog.DEBUG;
    private static final float CACHE_WARM_RATIO = 0.5f;

    /** The queue of requests coming in for triage. */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /** The queue of requests going out to the network. */
    private final RequestQueue mNetworkQueue;

    /** The cache to read from. */
    private final Cache mCache;

    /** For posting responses. */
    private final ResponseDelivery mDelivery;

    /** Used for telling us to die. */
    private volatile boolean mQuit = false;
    private boolean mInitialized;

    /**
     * Creates a new cache triage dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     *
     * @param cacheQueue Queue of incoming requests for triage
     * @param networkQueue Queue to post requests that require network to
     * @param cache Cache interface to use for resolution
     * @param delivery Delivery interface to use for posting responses
     */
    public CacheDispatcher(
            BlockingQueue<Request<?>> cacheQueue, RequestQueue networkQueue,
            Cache cache, ResponseDelivery delivery) {
        mCacheQueue = cacheQueue;
        mNetworkQueue = networkQueue;
        mCache = cache;
        mDelivery = delivery;
        mInitialized = false;
    }

    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @Override
    public void run() {
        if (DEBUG) VolleyLog.v("start new dispatcher");
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // Make a blocking call to initialize the cache.
        mCache.initialize();
        mInitialized = true;

        Request<?> request;
        while (true) {
            try {
                // Get a request from the cache triage queue, blocking until
                // at least one is available.
                request = mCacheQueue.take();
                request.addMarker("cache-queue-take");
                handleCacheRequest(request);

                // this exists for important samsung memory clearing :\
                request = null;
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
            }
        }
    }

    private void handleCacheRequest(final Request<?> request) {
        // If the request has been canceled, don't bother dispatching it.
        if (request.isCanceled()) {
            request.finish("cache-discard-canceled");
            return;
        }

        if (request.isFinished()) {
            request.finish("cache-request-already-finished");
            return;
        }

        // should the request be cache only.
        // if this is true it will never be sent to network.
        boolean cacheOnlyRequest = request.isJoined();

        // Attempt to retrieve this item from cache.
        Cache.Entry entry = mCache.get(request.getCacheKey());

        // if the entry doesn't exist then it is not in the cache
        if (entry == null) {
            request.addMarker("cache-miss");
            if (!cacheOnlyRequest) {
                // Cache miss; send off to the network dispatcher.
                mNetworkQueue.processNetworkRequest(request);
            }
            return;
        }

        // If it is completely expired, just send it to the network.
        if (entry.isExpired()) {
            request.addMarker("cache-hit-expired");
            if (!cacheOnlyRequest) {
                request.setCacheEntry(entry);
                mNetworkQueue.processNetworkRequest(request);
            }
            return;
        }

        // We have a cache hit; parse its data for delivery back to the request.
        request.addMarker("cache-hit");
        Response<?> response = request.parseNetworkResponse(
                new NetworkResponse(entry.data, entry.responseHeaders));
        request.addMarker("cache-hit-parsed");

        if (!entry.refreshNeeded()) {
            if (entry.softTtl - System.currentTimeMillis() < request.getSoftTTL() * CACHE_WARM_RATIO) {
                // caching 50% over so lets warm the cache
                // Post the intermediate response back to the user and have
                // the delivery then forward the request along to the network.
                request.markDelivery(Request.DeliveryType.Cache);
                mDelivery.postResponse(request, response, new Runnable() {
                    @Override
                    public void run() {
                        mNetworkQueue.processNetworkRequest(request);
                    }
                });
            } else {
                // Completely unexpired cache hit. Just deliver the response.
                request.markDelivery(Request.DeliveryType.Cache);
                mDelivery.postResponse(request, response);
            }
        } else if (request.getReturnStrategy() == Request.ReturnStrategy.CACHE_IF_NETWORK_FAILS) {
            // if CACHE_IF_NETWORK_FAILS prep cache response and send to network
            // this is a bit weird for a CACHE_IF_NETWORK_FAILS to be not allowed to go to
            // the network. But it 'could' happen if the request is joined
            if (!cacheOnlyRequest) {
                request.mCacheResponse = response;
                request.addMarker("cache-error-delivery-response-set");
                mNetworkQueue.processNetworkRequest(request);
            }
        } else {
            // Soft-expired cache hit. We can deliver the cached response,
            // but we need to also send the request to the network for
            // refreshing if allowed.
            request.addMarker("cache-hit-refresh-needed");
            request.setCacheEntry(entry);

            // Mark the response as intermediate.
            response.intermediate = true;

            // Post the intermediate response back to the user and have
            // the delivery then forward the request along to the network if allowed
            request.markDelivery(Request.DeliveryType.Cache);
            mDelivery.postResponse(request, response, cacheOnlyRequest ? null : new Runnable() {
                @Override
                public void run() {
                    mNetworkQueue.processNetworkRequest(request);
                }
            });
        }
    }

    // these methods below can get called from the UI thread.

    public boolean willMissCache(Request request) {
        // if not initialized return true. We don't know the state of the cache yet and waiting
        // to find out will block the thread
        if (!mInitialized) {
            return true;
        }

        Cache.Entry entry = mCache.getHeaders(request.getCacheKey());
        return entry == null || entry.isExpired();
    }

    public boolean willSkipNetwork(Request request) {
        // if not initialized return true. We don't know the state of the cache yet and waiting
        // to find out will block the thread
        if (!mInitialized) {
            return false;
        }

        Cache.Entry entry = mCache.getHeaders(request.getCacheKey());
        return entry != null && !entry.refreshNeeded();
    }

    public void expireCache(Request request) {
        Cache.Entry entry = mCache.getHeaders(request.getCacheKey());
        if (entry != null) {
            entry.expireCache();
            mCache.updateEntry(request.getCacheKey(), entry);
        }
    }

    public void expireSoftCache(Request request) {
        Cache.Entry entry = mCache.getHeaders(request.getCacheKey());
        if (entry != null) {
            entry.expireSoftCache();
            mCache.updateEntry(request.getCacheKey(), entry);
        }
    }
}
