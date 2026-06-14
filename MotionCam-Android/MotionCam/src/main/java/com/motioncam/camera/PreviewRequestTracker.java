package com.motioncam.camera;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the most recently issued asynchronous preview request per image.
 *
 * Preview generation is asynchronous and several requests can be in flight for the same
 * image (timestamp) while the user drags sliders, changes pages or switches preview sizes.
 * Because requests can complete out of submission order, an earlier (stale) request that
 * returns after a newer one must not overwrite the preview produced by the newer one.
 *
 * Callers allocate an id with {@link #newRequest(long)} before submitting a request and,
 * when the result arrives, check {@link #isCurrent(long, long)} to decide whether the
 * result is still the latest for that image or should be discarded.
 */
public class PreviewRequestTracker {
    private long mNextRequestId;
    private final Map<Long, Long> mLatestRequestId = new HashMap<>();

    /**
     * Allocate a new monotonically increasing request id and record it as the latest
     * request for the given image.
     *
     * @param imageKey identity of the image the request is for (the buffer timestamp)
     * @return the id that must be passed back when the request completes
     */
    public synchronized long newRequest(long imageKey) {
        long requestId = ++mNextRequestId;
        mLatestRequestId.put(imageKey, requestId);
        return requestId;
    }

    /**
     * @return true if {@code requestId} is the most recent request issued for
     *         {@code imageKey}, i.e. its result is still relevant and may be applied;
     *         false if a newer request has since been issued and the result is stale.
     */
    public synchronized boolean isCurrent(long imageKey, long requestId) {
        Long latest = mLatestRequestId.get(imageKey);
        return latest != null && latest.longValue() == requestId;
    }
}
