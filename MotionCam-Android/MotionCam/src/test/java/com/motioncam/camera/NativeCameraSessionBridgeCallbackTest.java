package com.motioncam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.graphics.Bitmap;

import com.motioncam.camera.NativeCameraSessionBridge.CameraExposureState;
import com.motioncam.camera.NativeCameraSessionBridge.CameraFocusState;
import com.motioncam.camera.NativeCameraSessionBridge.CameraRawPreviewListener;
import com.motioncam.camera.NativeCameraSessionBridge.CameraSessionListener;
import com.motioncam.camera.NativeCameraSessionBridge.CameraState;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Regression tests for the native -> Java callback boundary in {@link NativeCameraSessionBridge}.
 *
 * <p>The native camera layer delivers callbacks on its own threads and can fire late events after the
 * Java bridge has been torn down with {@code destroy()} or after RAW preview has been switched off with
 * {@code disableRawPreview()} (e.g. closing the camera or switching to another camera). Those teardown
 * paths clear {@code mListener} / {@code mRawPreviewListener}. Before the fix the callback methods
 * dereferenced those fields unconditionally, so a late event crashed with a {@link NullPointerException}
 * (or routed a stale session's event to a freshly attached listener). These tests lock in that the
 * callbacks are silently dropped once the listeners have been cleared, and are still forwarded while a
 * listener is present.</p>
 *
 * <p>The bridge is allocated WITHOUT running its constructors (which call into the native library that is
 * not available on the host JVM); only the pure-Java callback dispatch is exercised.</p>
 */
public class NativeCameraSessionBridgeCallbackTest {

    private static NativeCameraSessionBridge allocateBridgeWithoutNativeInit() throws Exception {
        // Allocate the instance without invoking any constructor so we never touch the native library.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        return (NativeCameraSessionBridge) allocateInstance.invoke(unsafe, NativeCameraSessionBridge.class);
    }

    private static void setListenerField(NativeCameraSessionBridge bridge, String name, Object value)
            throws Exception {
        Field field = NativeCameraSessionBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(bridge, value);
    }

    /** Invokes every session callback the native layer can deliver. */
    private static void invokeAllSessionCallbacks(NativeCameraSessionBridge bridge) {
        bridge.onCameraDisconnected();
        bridge.onCameraError(1);
        bridge.onCameraSessionStateChanged(1);
        bridge.onCameraExposureStatus(100, 1000L);
        bridge.onCameraAutoFocusStateChanged(0);
        bridge.onCameraAutoExposureStateChanged(0);
        bridge.onCameraHdrImageCaptureProgress(50);
        bridge.onCameraHdrImageCaptureFailed();
        bridge.onCameraHdrImageCaptureCompleted();
    }

    private static final class RecordingSessionListener implements CameraSessionListener {
        int calls = 0;

        @Override public void onCameraDisconnected() { calls++; }
        @Override public void onCameraError(int error) { calls++; }
        @Override public void onCameraSessionStateChanged(CameraState state) { calls++; }
        @Override public void onCameraExposureStatus(int iso, long exposureTime) { calls++; }
        @Override public void onCameraAutoFocusStateChanged(CameraFocusState state) { calls++; }
        @Override public void onCameraAutoExposureStateChanged(CameraExposureState state) { calls++; }
        @Override public void onCameraHdrImageCaptureProgress(int progress) { calls++; }
        @Override public void onCameraHdrImageCaptureFailed() { calls++; }
        @Override public void onCameraHdrImageCaptureCompleted() { calls++; }
    }

    private static final class RecordingRawPreviewListener implements CameraRawPreviewListener {
        int updated = 0;
        int created = 0;

        @Override public void onRawPreviewCreated(Bitmap bitmap) { created++; }
        @Override public void onRawPreviewUpdated() { updated++; }
    }

    @Test
    public void sessionCallbacks_areForwarded_whenListenerPresent() throws Exception {
        NativeCameraSessionBridge bridge = allocateBridgeWithoutNativeInit();
        RecordingSessionListener listener = new RecordingSessionListener();
        setListenerField(bridge, "mListener", listener);

        invokeAllSessionCallbacks(bridge);

        assertEquals(9, listener.calls);
    }

    @Test
    public void sessionCallbacks_areDropped_afterListenerCleared() throws Exception {
        NativeCameraSessionBridge bridge = allocateBridgeWithoutNativeInit();
        RecordingSessionListener listener = new RecordingSessionListener();
        setListenerField(bridge, "mListener", listener);

        // Simulate destroy() clearing the listener while the native layer still has events in flight.
        setListenerField(bridge, "mListener", null);

        // Must NOT throw: before the fix every one of these dereferenced a null mListener.
        invokeAllSessionCallbacks(bridge);

        assertEquals(0, listener.calls);
    }

    @Test
    public void rawPreviewUpdated_isForwarded_whenListenerPresent() throws Exception {
        NativeCameraSessionBridge bridge = allocateBridgeWithoutNativeInit();
        RecordingRawPreviewListener listener = new RecordingRawPreviewListener();
        setListenerField(bridge, "mRawPreviewListener", listener);

        bridge.onRawPreviewUpdated();

        assertEquals(1, listener.updated);
    }

    @Test
    public void rawPreviewUpdated_isDropped_afterListenerCleared() throws Exception {
        NativeCameraSessionBridge bridge = allocateBridgeWithoutNativeInit();

        // disableRawPreview()/destroy() leaves mRawPreviewListener null; a late preview tick is ignored.
        // Reaching the end without a NullPointerException is the assertion.
        bridge.onRawPreviewUpdated();
    }

    @Test
    public void rawPreviewBitmapNeeded_returnsNull_whenListenerCleared() throws Exception {
        NativeCameraSessionBridge bridge = allocateBridgeWithoutNativeInit();

        // With no RAW preview listener the bridge must short-circuit and return null without allocating
        // a Bitmap (the native side already tolerates a null bitmap return).
        assertNull(bridge.onRawPreviewBitmapNeeded(64, 64));
    }
}
