package com.motioncam.processor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for the export transaction policy.
 *
 * These pin the failure-path behaviour that was previously inconsistent: a run must
 * only be reported as completed when it produced an output, and the source RAW
 * container must only be deleted on success so a failed export can be retried.
 */
public class ExportPolicyTest {

    @Test
    public void reportsCompletedOnlyWhenAnOutputWasProduced() {
        assertTrue(ExportPolicy.shouldReportCompleted(true));
        assertFalse(ExportPolicy.shouldReportCompleted(false));
    }

    @Test
    public void deletesContainerAfterSuccessfulOnDiskExport() {
        assertTrue(ExportPolicy.shouldDeleteRawContainer(true, false));
    }

    @Test
    public void keepsContainerWhenExportFailed() {
        // The core fix: a failed export (no output) must leave the RAW zip on disk so
        // the capture can be retried instead of being silently lost.
        assertFalse(ExportPolicy.shouldDeleteRawContainer(false, false));
    }

    @Test
    public void neverDeletesForInMemoryCaptures() {
        // In-memory captures have no on-disk container to remove, regardless of outcome.
        assertFalse(ExportPolicy.shouldDeleteRawContainer(true, true));
        assertFalse(ExportPolicy.shouldDeleteRawContainer(false, true));
    }
}
