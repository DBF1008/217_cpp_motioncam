package com.motioncam.processor;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for {@link ProcessorReceiver} dispatch logic.
 *
 * Validates that each result code (STARTED, PROGRESS, PREVIEW_READY,
 * COMPLETED, FAILED) is dispatched to the correct {@link ProcessorReceiver.Receiver}
 * callback with the correct arguments.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ProcessorReceiverTest {

    private ProcessorReceiver.Receiver mMockReceiver;
    private ProcessorReceiver mProcessorReceiver;

    @Before
    public void setUp() {
        mMockReceiver = mock(ProcessorReceiver.Receiver.class);
        mProcessorReceiver = new ProcessorReceiver(new Handler());
        mProcessorReceiver.setReceiver(mMockReceiver);
    }

    // ---------------------------------------------------------------
    // PROCESS_CODE_STARTED dispatch
    // ---------------------------------------------------------------

    @Test
    public void testStartedDispatchesOnProcessingStarted() {
        Bundle bundle = new Bundle();
        bundle.putInt(ProcessorReceiver.PROCESS_CODE_PROGRESS_VALUE_KEY, 0);
        bundle.putString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY, "/data/raw.zip");

        mProcessorReceiver.onReceiveResult(ProcessorReceiver.PROCESS_CODE_STARTED, bundle);

        verify(mMockReceiver).onProcessingStarted();
        verifyNoMoreInteractions(mMockReceiver);
    }

    // ---------------------------------------------------------------
    // PROCESS_CODE_PROGRESS dispatch
    // ---------------------------------------------------------------

    @Test
    public void testProgressDispatchesCorrectValue() {
        Bundle bundle = new Bundle();
        bundle.putInt(ProcessorReceiver.PROCESS_CODE_PROGRESS_VALUE_KEY, 42);

        mProcessorReceiver.onReceiveResult(ProcessorReceiver.PROCESS_CODE_PROGRESS, bundle);

        verify(mMockReceiver).onProcessingProgress(42);
        verifyNoMoreInteractions(mMockReceiver);
    }

    // ---------------------------------------------------------------
    // PROCESS_CODE_PREVIEW_READY dispatch
    // ---------------------------------------------------------------

    @Test
    public void testPreviewReadyDispatchesOutputPath() {
        Bundle bundle = new Bundle();
        bundle.putString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY, "/preview/test.jpg");

        mProcessorReceiver.onReceiveResult(ProcessorReceiver.PROCESS_CODE_PREVIEW_READY, bundle);

        verify(mMockReceiver).onPreviewSaved("/preview/test.jpg");
        verifyNoMoreInteractions(mMockReceiver);
    }

    // ---------------------------------------------------------------
    // PROCESS_CODE_COMPLETED dispatch
    // ---------------------------------------------------------------

    @Test
    public void testCompletedDispatchesFileAndUri() {
        Bundle bundle = new Bundle();
        bundle.putString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY, "/preview/test.jpg");
        bundle.putString(ProcessorReceiver.PROCESS_CODE_CONTENT_URI_KEY, "content://media/external/images/123");
        bundle.putInt(ProcessorReceiver.PROCESS_CODE_PROGRESS_VALUE_KEY, 100);

        mProcessorReceiver.onReceiveResult(ProcessorReceiver.PROCESS_CODE_COMPLETED, bundle);

        ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
        ArgumentCaptor<Uri> uriCaptor = ArgumentCaptor.forClass(Uri.class);

        verify(mMockReceiver).onProcessingCompleted(fileCaptor.capture(), uriCaptor.capture());

        assertEquals("/preview/test.jpg", fileCaptor.getValue().getPath());
        assertEquals("content://media/external/images/123", uriCaptor.getValue().toString());
        verifyNoMoreInteractions(mMockReceiver);
    }

    // ---------------------------------------------------------------
    // PROCESS_CODE_FAILED dispatch (regression: was missing entirely)
    // ---------------------------------------------------------------

    @Test
    public void testFailedDispatchesErrorMessage() {
        Bundle bundle = new Bundle();
        bundle.putString(ProcessorReceiver.PROCESS_CODE_ERROR_MESSAGE_KEY, "MediaStore insert failed");
        bundle.putString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY, "/data/raw.zip");

        mProcessorReceiver.onReceiveResult(ProcessorReceiver.PROCESS_CODE_FAILED, bundle);

        verify(mMockReceiver).onProcessingFailed("MediaStore insert failed");
        verifyNoMoreInteractions(mMockReceiver);
    }

    @Test
    public void testFailedDefaultsToUnknownErrorWhenKeyMissing() {
        Bundle bundle = new Bundle();
        // Deliberately omit the error message key

        mProcessorReceiver.onReceiveResult(ProcessorReceiver.PROCESS_CODE_FAILED, bundle);

        verify(mMockReceiver).onProcessingFailed("Unknown error");
        verifyNoMoreInteractions(mMockReceiver);
    }

    // ---------------------------------------------------------------
    // Null receiver guard
    // ---------------------------------------------------------------

    @Test
    public void testNoCrashWhenDelegateReceiverIsNull() {
        mProcessorReceiver.setReceiver(null);

        Bundle bundle = new Bundle();
        bundle.putString(ProcessorReceiver.PROCESS_CODE_ERROR_MESSAGE_KEY, "error");

        // Should not throw
        mProcessorReceiver.onReceiveResult(ProcessorReceiver.PROCESS_CODE_FAILED, bundle);
        mProcessorReceiver.onReceiveResult(ProcessorReceiver.PROCESS_CODE_STARTED, bundle);
        mProcessorReceiver.onReceiveResult(ProcessorReceiver.PROCESS_CODE_COMPLETED, bundle);
    }

    // ---------------------------------------------------------------
    // Unknown result code
    // ---------------------------------------------------------------

    @Test
    public void testUnknownResultCodeIsIgnored() {
        mProcessorReceiver.onReceiveResult(9999, new Bundle());

        verifyNoInteractions(mMockReceiver);
    }
}
