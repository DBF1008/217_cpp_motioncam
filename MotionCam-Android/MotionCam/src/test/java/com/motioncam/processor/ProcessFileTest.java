package com.motioncam.processor;

import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for {@link ProcessorService.ProcessFile#call()}.
 *
 * These tests verify the three critical bug-fixes:
 * 1. STARTED notification carries the assembled bundle (not Bundle.EMPTY)
 * 2. contentUri == null → FAILED is sent (no NPE)
 * 3. Raw container is preserved on failure (not deleted)
 *
 * Uses Robolectric for Android framework classes and Mockito to mock
 * the native processor, avoiding the need to load the JNI library.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ProcessFileTest {

    @Rule
    public TemporaryFolder mTempFolder = new TemporaryFolder();

    private Context mContext;
    private ResultReceiver mMockResultReceiver;
    private NativeProcessor mMockNativeProcessor;
    private File mRawContainerFile;
    private File mPreviewDirectory;

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.getApplication();
        mMockResultReceiver = mock(ResultReceiver.class);
        mMockNativeProcessor = mock(NativeProcessor.class);

        mRawContainerFile = mTempFolder.newFile("test_capture.zip");
        mPreviewDirectory = mTempFolder.newFolder("preview");
    }

    /**
     * Create a ProcessFile with the mock native processor injected.
     */
    private ProcessorService.ProcessFile createProcessFile(boolean inMemory) {
        return new ProcessorService.ProcessFile(
                mContext, mRawContainerFile, mPreviewDirectory,
                inMemory, mMockResultReceiver, mMockNativeProcessor);
    }

    // ---------------------------------------------------------------
    // Bug #1: STARTED bundle must not be empty
    // ---------------------------------------------------------------

    @Test
    public void testStartedSendsPopulatedBundle() throws Exception {
        // Arrange: make native processing succeed and produce a JPEG
        when(mMockNativeProcessor.processInMemory(anyString(), any())).thenReturn(true);
        // Create the temp JPEG file so export can proceed
        mPreviewDirectory.mkdirs();
        new File(mPreviewDirectory, "test_capture.jpg").createNewFile();

        // Act
        createProcessFile(true).call();

        // Assert: STARTED was sent with a non-empty bundle containing the file path
        ArgumentCaptor<Integer> codeCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);

        verify(mMockResultReceiver, atLeastOnce()).send(codeCaptor.capture(), bundleCaptor.capture());

        // Find the STARTED call
        boolean foundStarted = false;
        for (int i = 0; i < codeCaptor.getAllValues().size(); i++) {
            if (codeCaptor.getAllValues().get(i) == ProcessorReceiver.PROCESS_CODE_STARTED) {
                Bundle startedBundle = bundleCaptor.getAllValues().get(i);
                assertNotNull("STARTED bundle must not be null", startedBundle);
                assertFalse("STARTED bundle must not be empty", startedBundle.isEmpty());
                assertEquals(mRawContainerFile.getPath(),
                        startedBundle.getString(ProcessorReceiver.PROCESS_CODE_OUTPUT_FILE_PATH_KEY));
                assertEquals(0,
                        startedBundle.getInt(ProcessorReceiver.PROCESS_CODE_PROGRESS_VALUE_KEY));
                foundStarted = true;
                break;
            }
        }
        assertTrue("STARTED notification must be sent", foundStarted);
    }

    // ---------------------------------------------------------------
    // Bug #2: contentUri null → FAILED, not NPE
    // ---------------------------------------------------------------

    @Test
    public void testNullContentUriTriggersFailedNotCompleted() throws Exception {
        // Arrange: native processing succeeds but doesn't produce a JPEG file
        // (simulates MediaStore insert returning null or JPEG not being created)
        when(mMockNativeProcessor.processInMemory(anyString(), any())).thenReturn(true);
        // Don't create the temp JPEG → contentUri will be null

        // Act
        Boolean result = createProcessFile(true).call();

        // Assert
        assertFalse("call() must return false when no output is generated", result);

        ArgumentCaptor<Integer> codeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockResultReceiver, atLeastOnce()).send(codeCaptor.capture(), any(Bundle.class));

        boolean foundFailed = false;
        boolean foundCompleted = false;
        for (int code : codeCaptor.getAllValues()) {
            if (code == ProcessorReceiver.PROCESS_CODE_FAILED) foundFailed = true;
            if (code == ProcessorReceiver.PROCESS_CODE_COMPLETED) foundCompleted = true;
        }

        assertTrue("FAILED must be sent when contentUri is null", foundFailed);
        assertFalse("COMPLETED must NOT be sent when contentUri is null", foundCompleted);
    }

    // ---------------------------------------------------------------
    // Bug #3: raw container preserved on processing failure
    // ---------------------------------------------------------------

    @Test
    public void testRawContainerPreservedOnProcessingException() throws Exception {
        // Arrange: native processing throws an exception
        doThrow(new IOException("Native processing crashed"))
                .when(mMockNativeProcessor).processFile(anyString(), anyString(), any());

        assertTrue("Raw container must exist before processing", mRawContainerFile.exists());

        // Act
        ProcessorService.ProcessFile processFile = new ProcessorService.ProcessFile(
                mContext, mRawContainerFile, mPreviewDirectory,
                false, mMockResultReceiver, mMockNativeProcessor);

        Boolean result = processFile.call();

        // Assert
        assertFalse("call() must return false on exception", result);
        assertTrue("Raw container must STILL EXIST after failure (for retry)",
                mRawContainerFile.exists());
    }

    @Test
    public void testFailedNotificationSentOnException() throws Exception {
        // Arrange
        doThrow(new IOException("Disk write error"))
                .when(mMockNativeProcessor).processFile(anyString(), anyString(), any());

        // Act
        ProcessorService.ProcessFile processFile = new ProcessorService.ProcessFile(
                mContext, mRawContainerFile, mPreviewDirectory,
                false, mMockResultReceiver, mMockNativeProcessor);

        processFile.call();

        // Assert: FAILED was sent with an error message
        ArgumentCaptor<Integer> codeCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);

        verify(mMockResultReceiver, atLeastOnce()).send(codeCaptor.capture(), bundleCaptor.capture());

        boolean foundFailed = false;
        for (int i = 0; i < codeCaptor.getAllValues().size(); i++) {
            if (codeCaptor.getAllValues().get(i) == ProcessorReceiver.PROCESS_CODE_FAILED) {
                Bundle failedBundle = bundleCaptor.getAllValues().get(i);
                String errorMsg = failedBundle.getString(ProcessorReceiver.PROCESS_CODE_ERROR_MESSAGE_KEY);
                assertNotNull("FAILED bundle must contain error message", errorMsg);
                assertTrue("Error message should mention the cause",
                        errorMsg.contains("Disk write error"));
                foundFailed = true;
                break;
            }
        }
        assertTrue("FAILED notification must be sent on exception", foundFailed);
    }

    // ---------------------------------------------------------------
    // Temp files cleaned up on failure
    // ---------------------------------------------------------------

    @Test
    public void testTempFilesCleanedUpOnFailure() throws Exception {
        // Arrange: processing will throw, but temp files exist
        doThrow(new IOException("Export failed"))
                .when(mMockNativeProcessor).processFile(anyString(), anyString(), any());

        File tempJpeg = new File(mPreviewDirectory, "test_capture.jpg");
        File tempDng = new File(mPreviewDirectory, "test_capture.dng");
        tempJpeg.createNewFile();
        tempDng.createNewFile();

        assertTrue("Temp JPEG must exist before failure", tempJpeg.exists());
        assertTrue("Temp DNG must exist before failure", tempDng.exists());

        // Act
        ProcessorService.ProcessFile processFile = new ProcessorService.ProcessFile(
                mContext, mRawContainerFile, mPreviewDirectory,
                false, mMockResultReceiver, mMockNativeProcessor);

        processFile.call();

        // Assert: temp files are cleaned up, but raw container is preserved
        assertFalse("Temp JPEG must be cleaned up after failure", tempJpeg.exists());
        assertFalse("Temp DNG must be cleaned up after failure", tempDng.exists());
        assertTrue("Raw container must be preserved for retry", mRawContainerFile.exists());
    }

    // ---------------------------------------------------------------
    // In-memory process returning false → FAILED sent
    // ---------------------------------------------------------------

    @Test
    public void testInMemoryReturnsFalseSendsFailed() throws Exception {
        // Arrange
        when(mMockNativeProcessor.processInMemory(anyString(), any())).thenReturn(false);

        // Act
        Boolean result = createProcessFile(true).call();

        // Assert
        assertFalse(result);

        ArgumentCaptor<Integer> codeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockResultReceiver, atLeastOnce()).send(codeCaptor.capture(), any(Bundle.class));

        boolean foundFailed = false;
        for (int code : codeCaptor.getAllValues()) {
            if (code == ProcessorReceiver.PROCESS_CODE_FAILED) {
                foundFailed = true;
                break;
            }
        }
        assertTrue("FAILED must be sent when in-memory processing returns false", foundFailed);
    }

    // ---------------------------------------------------------------
    // Null receiver doesn't crash
    // ---------------------------------------------------------------

    @Test
    public void testNullReceiverDoesNotCrash() throws Exception {
        // Arrange: no receiver at all
        when(mMockNativeProcessor.processInMemory(anyString(), any())).thenReturn(false);

        ProcessorService.ProcessFile processFile = new ProcessorService.ProcessFile(
                mContext, mRawContainerFile, mPreviewDirectory,
                true, null, mMockNativeProcessor);

        // Act — must not throw NullPointerException
        Boolean result = processFile.call();

        assertFalse(result);
    }

    // ---------------------------------------------------------------
    // fileNoExtension utility
    // ---------------------------------------------------------------

    @Test
    public void testFileNoExtensionStripsZipSuffix() {
        // Test via reflection or package-private access
        // The method is static and package-private, accessible from same package
        assertEquals("test_capture",
                invokeFileNoExtension("test_capture.zip"));
        assertEquals("my.photo",
                invokeFileNoExtension("my.photo.zip"));
        assertEquals("noextension",
                invokeFileNoExtension("noextension"));
    }

    /**
     * Helper to invoke the private static fileNoExtension method via reflection.
     */
    private String invokeFileNoExtension(String filename) {
        try {
            java.lang.reflect.Method method = ProcessorService.ProcessFile.class
                    .getDeclaredMethod("fileNoExtension", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, filename);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
