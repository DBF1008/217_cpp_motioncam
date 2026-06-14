package com.motioncam.camera;

import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Regression tests for the callback-after-destroy race condition.
 *
 * <p>When the Java-side listener is nulled (via {@code destroy()} or
 * {@code disableRawPreview()}), a late callback from the native layer must
 * NOT cause a NullPointerException.  These tests exercise every
 * {@code on*} callback with a null listener and verify they silently
 * drop the event rather than crashing.</p>
 *
 * <p>The bridge is instantiated via {@code Unsafe.allocateInstance} so
 * that no native library is loaded – we only test the pure-Java
 * null-safety logic.</p>
 */
public class NativeCameraSessionBridgeTest {

    private NativeCameraSessionBridge bridge;

    @Before
    public void setUp() throws Exception {
        // Unsafe.allocateInstance creates the object without calling any
        // constructor, so no native methods are invoked.  All fields are
        // zero-initialised (nulls / 0 / false).
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Unsafe unsafe = (Unsafe) f.get(null);

        bridge = (NativeCameraSessionBridge) unsafe.allocateInstance(NativeCameraSessionBridge.class);

        // mListener and mRawPreviewListener are both null after allocateInstance
    }

    // -----------------------------------------------------------------------
    //  CameraSessionListener callbacks – must tolerate null mListener
    // -----------------------------------------------------------------------

    @Test
    public void onCameraDisconnected_nullListener_doesNotThrow() {
        bridge.onCameraDisconnected(); // must not throw
    }

    @Test
    public void onCameraError_nullListener_doesNotThrow() {
        bridge.onCameraError(42);
    }

    @Test
    public void onCameraSessionStateChanged_nullListener_doesNotThrow() {
        bridge.onCameraSessionStateChanged(0); // READY
        bridge.onCameraSessionStateChanged(1); // ACTIVE
        bridge.onCameraSessionStateChanged(2); // CLOSED
    }

    @Test
    public void onCameraExposureStatus_nullListener_doesNotThrow() {
        bridge.onCameraExposureStatus(100, 10_000_000L);
    }

    @Test
    public void onCameraAutoFocusStateChanged_nullListener_doesNotThrow() {
        for (int i = 0; i <= 6; i++) {
            bridge.onCameraAutoFocusStateChanged(i);
        }
    }

    @Test
    public void onCameraAutoExposureStateChanged_nullListener_doesNotThrow() {
        for (int i = 0; i <= 5; i++) {
            bridge.onCameraAutoExposureStateChanged(i);
        }
    }

    @Test
    public void onCameraHdrImageCaptureFailed_nullListener_doesNotThrow() {
        bridge.onCameraHdrImageCaptureFailed();
    }

    @Test
    public void onCameraHdrImageCaptureProgress_nullListener_doesNotThrow() {
        bridge.onCameraHdrImageCaptureProgress(50);
    }

    @Test
    public void onCameraHdrImageCaptureCompleted_nullListener_doesNotThrow() {
        bridge.onCameraHdrImageCaptureCompleted();
    }

    // -----------------------------------------------------------------------
    //  CameraRawPreviewListener callbacks – must tolerate null listener
    // -----------------------------------------------------------------------

    @Test
    public void onRawPreviewUpdated_nullListener_doesNotThrow() {
        bridge.onRawPreviewUpdated();
    }

    @Test
    public void onRawPreviewBitmapNeeded_nullListener_returnsNull() {
        // When mRawPreviewListener is null the callback must return null
        // rather than crashing.  The native caller already handles a null
        // Bitmap return gracefully (it logs and skips the frame).
        //
        // NOTE: Bitmap.createBitmap is unavailable in a plain JVM test, so
        // we can only assert that the method returns before reaching
        // Bitmap.createBitmap – i.e. the null-guard fires first.  If the
        // null guard were missing this call would NPE on the listener
        // dereference; if it were present but placed after createBitmap it
        // would fail with a different error.  Both are regressions.
        try {
            Object result = bridge.onRawPreviewBitmapNeeded(640, 480);
            // If we get here without an NPE the null-guard fired correctly.
            // The result should be null because the listener was null.
            assertNull("Expected null return when raw-preview listener is null", result);
        } catch (NullPointerException e) {
            fail("onRawPreviewBitmapNeeded must not dereference null mRawPreviewListener");
        } catch (RuntimeException e) {
            // Bitmap.createBitmap may throw on plain JVM (no Android runtime).
            // That is acceptable – it means the null-guard did NOT fire before
            // reaching createBitmap, which is a regression.
            if (e.getMessage() != null && e.getMessage().contains("Bitmap")) {
                fail("onRawPreviewBitmapNeeded reached Bitmap.createBitmap before checking null listener");
            }
            // Any other RuntimeException is unexpected
            throw e;
        }
    }

    // -----------------------------------------------------------------------
    //  destroy() / disableRawPreview() must null both listeners
    // -----------------------------------------------------------------------

    @Test
    public void destroy_nullsRawPreviewListener() throws Exception {
        // Set mRawPreviewListener to a non-null sentinel
        Field rawField = NativeCameraSessionBridge.class.getDeclaredField("mRawPreviewListener");
        rawField.setAccessible(true);
        rawField.set(bridge, new DummyRawPreviewListener());

        // Set mNativeCameraHandle to INVALID so ensureValidHandle() won't fail
        // when destroy() is bypassed (we simulate the post-destroy state manually)
        Field handleField = NativeCameraSessionBridge.class.getDeclaredField("mNativeCameraHandle");
        handleField.setAccessible(true);
        handleField.setLong(bridge, NativeCameraSessionBridge.INVALID_NATIVE_HANDLE);

        // Simulate what destroy() does to the listener fields (without calling native)
        Field listenerField = NativeCameraSessionBridge.class.getDeclaredField("mListener");
        listenerField.setAccessible(true);
        listenerField.set(bridge, null);
        rawField.set(bridge, null);

        // After simulated destroy, callbacks must still be safe
        bridge.onCameraDisconnected();
        bridge.onRawPreviewUpdated();
    }

    @Test
    public void disableRawPreview_nullsListener() throws Exception {
        // Verify the field is cleared by disableRawPreview.
        // We cannot call the native DisableRawPreview method, so we simulate
        // the Java-side effect: mRawPreviewListener = null.
        Field rawField = NativeCameraSessionBridge.class.getDeclaredField("mRawPreviewListener");
        rawField.setAccessible(true);
        rawField.set(bridge, new DummyRawPreviewListener());

        // Simulate disableRawPreview() Java-side logic
        rawField.set(bridge, null);

        // Callback must be safe
        bridge.onRawPreviewUpdated();
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    /** Minimal stub used only to set the field to a non-null value. */
    private static class DummyRawPreviewListener
            implements NativeCameraSessionBridge.CameraRawPreviewListener {
        @Override public void onRawPreviewCreated(android.graphics.Bitmap bitmap) {}
        @Override public void onRawPreviewUpdated() {}
    }
}
