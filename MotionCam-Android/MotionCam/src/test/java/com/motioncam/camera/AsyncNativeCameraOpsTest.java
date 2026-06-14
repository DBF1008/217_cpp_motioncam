package com.motioncam.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.util.Size;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class AsyncNativeCameraOpsTest {

    @Mock
    private NativeCameraSessionBridge mMockBridge;

    private AsyncNativeCameraOps mOps;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        mOps = new AsyncNativeCameraOps(mMockBridge);
    }

    /**
     * Regression test: close() must shut down BOTH mBackgroundProcessor and
     * mQueuedBackgroundProcessor. Previously only mBackgroundProcessor was shut down,
     * leaking the queued executor thread.
     */
    @Test
    public void close_shutsDownBothExecutors() throws Exception {
        mOps.close();

        ExecutorService bgProcessor = getPrivateField(mOps, "mBackgroundProcessor");
        ExecutorService queuedProcessor = getPrivateField(mOps, "mQueuedBackgroundProcessor");

        assertTrue("mBackgroundProcessor should be shut down after close()",
                bgProcessor.isShutdown());
        assertTrue("mQueuedBackgroundProcessor should be shut down after close()",
                queuedProcessor.isShutdown());
    }

    @Test
    public void close_setsClosedFlag() {
        assertFalse(mOps.isClosed());
        mOps.close();
        assertTrue(mOps.isClosed());
    }

    /**
     * Regression test: After close(), generatePreview must not submit tasks
     * that call into the native session bridge.
     */
    @Test
    public void generatePreview_afterClose_doesNotCallNative() throws Exception {
        NativeCameraBuffer buffer = createMockBuffer();
        PostProcessSettings settings = new PostProcessSettings();

        mOps.close();

        mOps.generatePreview(buffer, settings,
                AsyncNativeCameraOps.PreviewSize.SMALL, null,
                mock(AsyncNativeCameraOps.PreviewListener.class), false);

        // Give a small window for any potential submission to happen
        Thread.sleep(200);

        verify(mMockBridge, never()).createPreviewImage(anyLong(), any(), anyInt(), any());
    }

    /**
     * Regression test: After close(), captureImage must not submit tasks.
     */
    @Test
    public void captureImage_afterClose_doesNotCallNative() throws Exception {
        mOps.close();

        mOps.captureImage(1L, 3, new PostProcessSettings(), "/tmp/out",
                mock(AsyncNativeCameraOps.CaptureImageListener.class));

        Thread.sleep(200);

        verify(mMockBridge, never()).captureImage(anyLong(), anyInt(), any(), any());
    }

    /**
     * Regression test: After close(), estimateSettings must not submit tasks.
     */
    @Test
    public void estimateSettings_afterClose_doesNotCallNative() throws Exception {
        mOps.close();

        mOps.estimateSettings(false, 0f,
                mock(AsyncNativeCameraOps.PostProcessSettingsListener.class));

        Thread.sleep(200);

        verify(mMockBridge, never()).estimatePostProcessSettings(
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyFloat());
    }

    /**
     * Regression test: After close(), measureSharpness must not submit tasks.
     */
    @Test
    public void measureSharpness_afterClose_doesNotCallNative() throws Exception {
        NativeCameraBuffer buffer = createMockBuffer();

        mOps.close();

        mOps.measureSharpness(Arrays.asList(buffer),
                mock(AsyncNativeCameraOps.SharpnessMeasuredListener.class));

        Thread.sleep(200);

        verify(mMockBridge, never()).measureSharpness(anyLong());
    }

    /**
     * Regression test: A generatePreview task that was already running when close()
     * is called must not deliver its callback to the listener.
     */
    @Test
    public void generatePreview_inFlight_doesNotCallbackAfterClose() throws Exception {
        // Use a latch so we can control when the native call returns
        CountDownLatch nativeCallStarted = new CountDownLatch(1);
        CountDownLatch closeCompleted = new CountDownLatch(1);

        org.mockito.Mockito.doAnswer(invocation -> {
            nativeCallStarted.countDown();
            // Wait until close() finishes before letting the task continue
            closeCompleted.await(2, TimeUnit.SECONDS);
            return null;
        }).when(mMockBridge).createPreviewImage(anyLong(), any(), anyInt(), any());

        // Mock getPreviewSize to return a valid size
        org.mockito.Mockito.when(mMockBridge.getPreviewSize(anyInt())).thenReturn(new Size(100, 100));

        NativeCameraBuffer buffer = createMockBuffer();
        PostProcessSettings settings = new PostProcessSettings();
        AtomicBoolean callbackFired = new AtomicBoolean(false);

        // Submit a non-skippable (queued) preview generation
        mOps.generatePreview(buffer, settings,
                AsyncNativeCameraOps.PreviewSize.SMALL, null,
                (b, img) -> callbackFired.set(true), false);

        // Wait for the task to start the native call
        assertTrue("Native call should have started",
                nativeCallStarted.await(2, TimeUnit.SECONDS));

        // Now close while the task is mid-flight
        mOps.close();
        closeCompleted.countDown();

        // Pump the Robolectric main looper to flush any residual messages
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertFalse("Callback should NOT fire after close()", callbackFired.get());
    }

    /**
     * Regression test: generatePreview with canSkip=true uses mBackgroundProcessor.
     * After close(), it must not submit tasks on that executor either.
     */
    @Test
    public void generatePreview_canSkip_afterClose_doesNotCallNative() throws Exception {
        NativeCameraBuffer buffer = createMockBuffer();
        PostProcessSettings settings = new PostProcessSettings();

        mOps.close();

        mOps.generatePreview(buffer, settings,
                AsyncNativeCameraOps.PreviewSize.LARGE, null,
                mock(AsyncNativeCameraOps.PreviewListener.class), true);

        Thread.sleep(200);

        verify(mMockBridge, never()).createPreviewImage(anyLong(), any(), anyInt(), any());
    }

    // --- Helpers ---

    private NativeCameraBuffer createMockBuffer() {
        return new NativeCameraBuffer(1000L, 100, 10000000L, 2, 1920, 1080);
    }

    @SuppressWarnings("unchecked")
    private <T> T getPrivateField(Object obj, String fieldName) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(obj);
    }
}
