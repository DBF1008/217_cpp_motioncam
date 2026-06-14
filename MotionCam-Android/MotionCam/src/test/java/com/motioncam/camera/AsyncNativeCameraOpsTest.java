package com.motioncam.camera;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression tests for {@link AsyncNativeCameraOps#close()} lifecycle.
 *
 * <p>Background: {@code PostProcessFragment.onDestroy()} calls
 * {@code AsyncNativeCameraOps.close()} and then destroys the native camera
 * session. The old {@code close()} only shut down {@code mBackgroundProcessor}
 * and leaked {@code mQueuedBackgroundProcessor}, so already-queued preview tasks
 * could still call into the (now destroyed) native session and post stale
 * callbacks to the main thread after the fragment was gone.
 *
 * <p>These tests pin down the fixed contract: once {@code close()} returns, no
 * task on either executor runs its body, and no result callback is delivered.
 *
 * <p>Run with: {@code ./gradlew :MotionCam:testDebugUnitTest}
 */
public class AsyncNativeCameraOpsTest {

    private final NativeCameraSessionBridge bridge = mock(NativeCameraSessionBridge.class);
    private final Handler handler = mock(Handler.class);
    private final AsyncNativeCameraOps ops = new AsyncNativeCameraOps(bridge, handler);

    private static NativeCameraBuffer buffer() {
        return new NativeCameraBuffer(
                123L, 100, 1000L,
                NativeCameraBuffer.ScreenOrientation.LANDSCAPE.value,
                640, 480);
    }

    private static PostProcessSettings settings() {
        return new PostProcessSettings();
    }

    /**
     * The skippable preview path (mBackgroundProcessor) must be inert after close:
     * no native call, no callback, nothing even submitted.
     */
    @Test
    public void afterClose_skipPath_doesNotTouchNativeOrListener() {
        AsyncNativeCameraOps.PreviewListener listener = mock(AsyncNativeCameraOps.PreviewListener.class);

        ops.close();
        ops.generatePreview(buffer(), settings(), AsyncNativeCameraOps.PreviewSize.SMALL, null, listener, true);

        verify(bridge, never()).createPreviewImage(anyLong(), any(), anyInt(), any());
        verify(listener, never()).onPreviewAvailable(any(), any());
        verify(handler, never()).post(any());
    }

    /**
     * The non-skippable preview path uses mQueuedBackgroundProcessor, the executor
     * that the old close() leaked. After close it too must be inert. This is the
     * core regression: a queued preview task must not reach the native session
     * once the owning fragment is being destroyed.
     */
    @Test
    public void afterClose_queuedPath_doesNotTouchNativeOrListener() {
        AsyncNativeCameraOps.PreviewListener listener = mock(AsyncNativeCameraOps.PreviewListener.class);

        ops.close();
        ops.generatePreview(buffer(), settings(), AsyncNativeCameraOps.PreviewSize.SMALL, null, listener, false);

        verify(bridge, never()).createPreviewImage(anyLong(), any(), anyInt(), any());
        verify(listener, never()).onPreviewAvailable(any(), any());
        verify(handler, never()).post(any());
    }

    /**
     * Callback-invalidation boundary: a background task that finishes and posts its
     * result to the main thread BEFORE close() must have that callback suppressed if
     * close() happens before the main thread runs it (the real-world race where the
     * fragment is destroyed between the work finishing and the post being dispatched).
     */
    @Test
    public void postedCallback_isSuppressedAfterClose() throws Exception {
        AsyncNativeCameraOps.CaptureImageListener listener = mock(AsyncNativeCameraOps.CaptureImageListener.class);

        // Capture the runnable that would run on the main thread instead of dispatching it.
        AtomicReference<Runnable> posted = new AtomicReference<>();
        CountDownLatch postedLatch = new CountDownLatch(1);
        when(handler.post(any())).thenAnswer(invocation -> {
            posted.set(invocation.getArgument(0));
            postedLatch.countDown();
            return true;
        });

        // bridge.captureImage(...) is void; the default mock is a no-op.
        ops.captureImage(42L, 3, settings(), "/tmp/out", listener);

        // Wait until the background task has produced its main-thread callback.
        assertTrue("background task never posted a result", postedLatch.await(2, TimeUnit.SECONDS));

        // Fragment/native session torn down before the callback runs.
        ops.close();

        // Dispatching the captured callback now must NOT reach the listener.
        posted.get().run();

        verify(listener, never()).onCaptured(anyLong());
    }

    /**
     * Positive control: without close(), the same pipeline delivers the callback,
     * proving the close() guard does not break normal operation.
     */
    @Test
    public void postedCallback_isDeliveredWhenNotClosed() throws Exception {
        AsyncNativeCameraOps.CaptureImageListener listener = mock(AsyncNativeCameraOps.CaptureImageListener.class);

        AtomicReference<Runnable> posted = new AtomicReference<>();
        CountDownLatch postedLatch = new CountDownLatch(1);
        when(handler.post(any())).thenAnswer(invocation -> {
            posted.set(invocation.getArgument(0));
            postedLatch.countDown();
            return true;
        });

        ops.captureImage(42L, 3, settings(), "/tmp/out", listener);

        assertTrue("background task never posted a result", postedLatch.await(2, TimeUnit.SECONDS));

        // Not closed: dispatching the callback reaches the listener.
        posted.get().run();

        verify(listener).onCaptured(42L);
    }

    /** close() must be safe to call more than once (onDestroy may run after other teardown). */
    @Test
    public void close_isIdempotent() {
        ops.close();
        ops.close();
    }
}
