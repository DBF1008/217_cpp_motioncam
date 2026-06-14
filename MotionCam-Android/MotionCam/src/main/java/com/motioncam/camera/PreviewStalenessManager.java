package com.motioncam.camera;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks preview request generations to detect stale (out-of-order) results.
 *
 * When the user rapidly changes post-processing sliders, multiple async preview
 * requests are submitted for the same image buffer. Without staleness tracking,
 * an older request that completes after a newer one would overwrite the preview
 * with outdated settings, causing visible flicker.
 *
 * Usage:
 *   1. When submitting a preview request: int gen = manager.nextGeneration();
 *   2. Store gen in the adapter item: item.generation = gen;
 *   3. When result arrives: if (manager.isCurrent(gen, item.generation)) accept
 *
 * Thread-safety: nextGeneration() is atomic and safe from any thread.
 * isCurrent() only reads primitives and is safe from any thread.
 */
public class PreviewStalenessManager {

    private final AtomicInteger mGenerationCounter = new AtomicInteger(0);

    /**
     * Allocate the next generation ID. Call this when submitting a new
     * preview request, or when invalidating in-flight requests (e.g., when
     * setting a direct bitmap that should not be overwritten by pending results).
     *
     * @return a monotonically increasing generation ID (starts at 1)
     */
    public int nextGeneration() {
        return mGenerationCounter.incrementAndGet();
    }

    /**
     * Check whether a completed result is still current for the given item.
     *
     * @param resultGeneration  the generation captured when the request was submitted
     * @param itemGeneration    the item's current expected generation
     * @return true if resultGeneration matches itemGeneration (result is fresh)
     */
    public boolean isCurrent(int resultGeneration, int itemGeneration) {
        return resultGeneration == itemGeneration;
    }

    /**
     * @return the most recently allocated generation ID (for testing/debugging)
     */
    public int currentGeneration() {
        return mGenerationCounter.get();
    }
}
