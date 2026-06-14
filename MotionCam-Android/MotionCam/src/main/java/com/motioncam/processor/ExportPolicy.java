package com.motioncam.processor;

/**
 * Pure (Android-free) transaction policy for a single process-and-export attempt.
 *
 * The export of a processed capture has two coupled side effects that must stay
 * consistent: the status reported back to the UI, and whether the source RAW
 * container is removed from disk. Keeping the rules here (a) gives them a single
 * source of truth and (b) lets them be unit tested without an emulator.
 */
final class ExportPolicy {
    private ExportPolicy() {
    }

    /**
     * A run only counts as completed when the export actually produced an output the
     * user can open (a content {@code Uri}). If no output was produced - e.g. the
     * MediaStore insert failed or the JPEG was never generated - the run failed and a
     * {@code COMPLETED} receipt must not be sent.
     */
    static boolean shouldReportCompleted(boolean producedOutput) {
        return producedOutput;
    }

    /**
     * The source RAW container may only be deleted after a successful export, so a
     * failed export leaves the original zip on disk for a retry. In-memory captures
     * have no on-disk container to remove.
     */
    static boolean shouldDeleteRawContainer(boolean producedOutput, boolean processInMemory) {
        return producedOutput && !processInMemory;
    }
}
