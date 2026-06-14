package com.motioncam.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for {@link PreviewRequestTracker}, which guards post-process previews
 * against stale asynchronous results overwriting newer ones for the same image.
 */
public class PreviewRequestTrackerTest {

    private static final long IMAGE_A = 1000L;
    private static final long IMAGE_B = 2000L;

    @Test
    public void newRequest_returnsMonotonicallyIncreasingIds() {
        PreviewRequestTracker tracker = new PreviewRequestTracker();

        long first = tracker.newRequest(IMAGE_A);
        long second = tracker.newRequest(IMAGE_A);
        long third = tracker.newRequest(IMAGE_B);

        assertTrue(second > first);
        assertTrue(third > second);
    }

    @Test
    public void isCurrent_trueForTheLatestRequest() {
        PreviewRequestTracker tracker = new PreviewRequestTracker();

        long requestId = tracker.newRequest(IMAGE_A);

        assertTrue(tracker.isCurrent(IMAGE_A, requestId));
    }

    @Test
    public void isCurrent_falseForUnknownImageOrRequest() {
        PreviewRequestTracker tracker = new PreviewRequestTracker();

        // Nothing has been issued yet for this image.
        assertFalse(tracker.isCurrent(IMAGE_A, 1L));

        long requestId = tracker.newRequest(IMAGE_A);

        // An id that was never handed out for this image.
        assertFalse(tracker.isCurrent(IMAGE_A, requestId + 1));
    }

    /**
     * The core regression: an earlier request that completes AFTER a newer request for the
     * same image must be reported as stale so its result is discarded, while the newer
     * request stays current. This is the "submitted earlier, returned later" case that
     * previously let an old preview overwrite the user's latest parameters and made the
     * preview jump back and forth.
     */
    @Test
    public void staleLateResult_isDroppedAndNewestWins() {
        PreviewRequestTracker tracker = new PreviewRequestTracker();

        long older = tracker.newRequest(IMAGE_A);
        long newer = tracker.newRequest(IMAGE_A);

        assertNotEquals(older, newer);

        // The older request finishes last (out of order) -> must be treated as stale.
        assertFalse(tracker.isCurrent(IMAGE_A, older));

        // The newer request's result is still the one that should be applied.
        assertTrue(tracker.isCurrent(IMAGE_A, newer));
    }

    @Test
    public void requestsForDifferentImagesAreIndependent() {
        PreviewRequestTracker tracker = new PreviewRequestTracker();

        long requestA = tracker.newRequest(IMAGE_A);
        long requestB = tracker.newRequest(IMAGE_B);

        // Issuing a request for image B must not invalidate image A's latest request.
        assertTrue(tracker.isCurrent(IMAGE_A, requestA));
        assertTrue(tracker.isCurrent(IMAGE_B, requestB));
    }

    @Test
    public void supersededRequestForOneImageDoesNotAffectAnother() {
        PreviewRequestTracker tracker = new PreviewRequestTracker();

        long staleA = tracker.newRequest(IMAGE_A);
        long currentB = tracker.newRequest(IMAGE_B);
        long currentA = tracker.newRequest(IMAGE_A);

        assertFalse(tracker.isCurrent(IMAGE_A, staleA));
        assertTrue(tracker.isCurrent(IMAGE_A, currentA));
        assertTrue(tracker.isCurrent(IMAGE_B, currentB));
    }

    /**
     * Simulates a fast slider drag: many requests are issued in quick succession for the
     * same image and complete in arbitrary order. Only the result of the last request
     * issued may be applied.
     */
    @Test
    public void onlyTheLastOfManyRapidRequestsIsCurrent() {
        PreviewRequestTracker tracker = new PreviewRequestTracker();

        long[] ids = new long[16];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = tracker.newRequest(IMAGE_A);
        }

        for (int i = 0; i < ids.length - 1; i++) {
            assertFalse("request " + i + " should be stale", tracker.isCurrent(IMAGE_A, ids[i]));
        }

        assertTrue(tracker.isCurrent(IMAGE_A, ids[ids.length - 1]));
    }
}
