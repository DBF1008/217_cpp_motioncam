package com.motioncam.camera;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.util.Size;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncNativeCameraOps implements Closeable {
    public enum PreviewSize {
        SMALL(8),
        MEDIUM(4),
        LARGE(2);

        private final int scale;

        PreviewSize(int scale) {
            this.scale = scale;
        }
    }

    private final ExecutorService mQueuedBackgroundProcessor = Executors.newSingleThreadExecutor();

    private final ExecutorService mBackgroundProcessor =
            new ThreadPoolExecutor(
                1, 1, 500, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardOldestPolicy());

    // Once closed, no further background work is accepted and no callbacks are
    // delivered. This protects against tasks that were already queued continuing
    // to run after the owning fragment (and the native camera session) is gone.
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    private final NativeCameraSessionBridge mCameraSessionBridge;
    private final Handler mMainHandler;
    private Size mUnscaledSize;

    public interface PreviewListener {
        void onPreviewAvailable(NativeCameraBuffer buffer, Bitmap image);
    }

    public interface PostProcessSettingsListener {
        void onSettingsEstimated(PostProcessSettings settings);
    }

    public interface CaptureImageListener {
        void onCaptured(long handle);
    }

    public interface SharpnessMeasuredListener {
        void onSharpnessMeasured(List<Pair<NativeCameraBuffer, Double>> sharpnessList);
    }

    public AsyncNativeCameraOps(NativeCameraSessionBridge cameraSessionBridge) {
        this(cameraSessionBridge, new Handler(Looper.getMainLooper()));
    }

    // Visible for testing: lets unit tests supply a Handler so the main-thread
    // dispatch (and the close() callback-invalidation boundary) can be observed
    // without a real Looper.
    AsyncNativeCameraOps(NativeCameraSessionBridge cameraSessionBridge, Handler mainHandler) {
        mCameraSessionBridge = cameraSessionBridge;
        mMainHandler = mainHandler;
    }

    @Override
    public void close() {
        // Idempotent and safe to call from the main thread (onDestroy()).
        if(!mClosed.compareAndSet(false, true)) {
            return;
        }

        // Shut down BOTH executors. Previously only mBackgroundProcessor was
        // stopped, which leaked mQueuedBackgroundProcessor and let already-queued
        // preview tasks keep calling into the native session after it had been
        // destroyed. Awaiting termination also ensures no preview task is still
        // executing createPreviewImage() by the time the caller destroys the
        // native camera handle.
        shutdownAndAwait(mQueuedBackgroundProcessor);
        shutdownAndAwait(mBackgroundProcessor);
    }

    private static void shutdownAndAwait(ExecutorService executor) {
        executor.shutdown();

        try {
            if(!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void submit(ExecutorService executor, Runnable task) {
        // Don't accept new work once closed: the task would call into a destroyed
        // native session and/or deliver a callback to a torn-down fragment.
        if(mClosed.get()) {
            return;
        }

        try {
            executor.submit(() -> {
                // close() may have happened while this task sat in the queue.
                if(mClosed.get()) {
                    return;
                }

                task.run();
            });
        }
        catch (RejectedExecutionException e) {
            // close() raced with this submission; dropping the task is correct.
        }
    }

    private void postResult(Runnable callback) {
        mMainHandler.post(() -> {
            // The fragment and native session may have been destroyed between the
            // background work finishing and this callback running on the main
            // thread. Suppress the callback in that case.
            if(mClosed.get()) {
                return;
            }

            callback.run();
        });
    }

    public void captureImage(long bufferHandle, int numSaveImages, PostProcessSettings settings, String outputPath, CaptureImageListener listener) {
        submit(mBackgroundProcessor, () -> {
            mCameraSessionBridge.captureImage(bufferHandle, numSaveImages, settings, outputPath);
            postResult(() -> listener.onCaptured(bufferHandle));
        });
    }

    public void estimateSettings(boolean basicSettings, float shadowsBias, PostProcessSettingsListener listener) {
        submit(mBackgroundProcessor, () -> {
            try {
                PostProcessSettings result = mCameraSessionBridge.estimatePostProcessSettings(basicSettings, shadowsBias);
                postResult(() -> listener.onSettingsEstimated(result));

            }
            catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void measureSharpness(List<NativeCameraBuffer> buffers, SharpnessMeasuredListener listener) {
        if(buffers.isEmpty())
            return;

        submit(mBackgroundProcessor, () -> {
            List<Pair<NativeCameraBuffer, Double>> result = new ArrayList<>();

            for(NativeCameraBuffer buffer : buffers) {
                double sharpness = mCameraSessionBridge.measureSharpness(buffer.timestamp);
                result.add(new Pair<>(buffer, sharpness));
            }

            result.sort((l, r) -> l.second.compareTo(r.second));

            postResult(() -> listener.onSharpnessMeasured(result));
        });
    }

    public Size getPreviewSize(PreviewSize generateSize, NativeCameraBuffer buffer) {
        if (mUnscaledSize == null)
            mUnscaledSize = mCameraSessionBridge.getPreviewSize(1);

        if(mUnscaledSize == null)
            return new Size(0, 0);

        int width = mUnscaledSize.getWidth() / generateSize.scale;
        int height = mUnscaledSize.getHeight() / generateSize.scale;

        if( buffer.screenOrientation == NativeCameraBuffer.ScreenOrientation.PORTRAIT ||
            buffer.screenOrientation == NativeCameraBuffer.ScreenOrientation.REVERSE_PORTRAIT) {

            int temp = width;

            width = height;
            height = temp;

        }

        return new Size(width, height);
    }

    public void generatePreview(NativeCameraBuffer buffer,
                                PostProcessSettings settings,
                                PreviewSize generateSize,
                                Bitmap useBitmap,
                                PreviewListener listener,
                                boolean canSkip)
    {
        PostProcessSettings postProcessSettings = settings.clone();

        ExecutorService executor = canSkip ? mBackgroundProcessor : mQueuedBackgroundProcessor;
        submit(executor, () -> {
            Bitmap preview = useBitmap;
            Size size = getPreviewSize(generateSize, buffer);

            // Create bitmap if the provided one was incompatible (or null)
            if( preview == null ||
                preview.getWidth() != size.getWidth() ||
                preview.getHeight() != size.getHeight() )
            {
                preview = Bitmap.createBitmap(size.getWidth(), size.getHeight(), Bitmap.Config.ARGB_8888);
            }

            mCameraSessionBridge.createPreviewImage(
                    buffer.timestamp,
                    postProcessSettings,
                    generateSize.scale,
                    preview);

            final Bitmap resultBitmap = preview;

            // On the main thread, let listeners know that an image is ready
            postResult(() -> listener.onPreviewAvailable(buffer, resultBitmap));
        });
    }
}
