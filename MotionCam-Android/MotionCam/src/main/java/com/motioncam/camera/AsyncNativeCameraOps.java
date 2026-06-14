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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    private ExecutorService mQueuedBackgroundProcessor = Executors.newSingleThreadExecutor();

    private ExecutorService mBackgroundProcessor =
            new ThreadPoolExecutor(
                1, 1, 500, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardOldestPolicy());

    private final NativeCameraSessionBridge mCameraSessionBridge;
    private final Handler mMainHandler;
    private Size mUnscaledSize;
    private volatile boolean mClosed = false;

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
        mCameraSessionBridge = cameraSessionBridge;
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdownNow();

        try {
            if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        mClosed = true;

        shutdownExecutor(mBackgroundProcessor);
        shutdownExecutor(mQueuedBackgroundProcessor);

        mMainHandler.removeCallbacksAndMessages(null);
    }

    public boolean isClosed() {
        return mClosed;
    }

    public void captureImage(long bufferHandle, int numSaveImages, PostProcessSettings settings, String outputPath, CaptureImageListener listener) {
        if (mClosed)
            return;

        mBackgroundProcessor.submit(() -> {
            if (mClosed)
                return;

            mCameraSessionBridge.captureImage(bufferHandle, numSaveImages, settings, outputPath);

            if (mClosed)
                return;

            mMainHandler.post(() -> {
                if (!mClosed)
                    listener.onCaptured(bufferHandle);
            });
        });
    }

    public void estimateSettings(boolean basicSettings, float shadowsBias, PostProcessSettingsListener listener) {
        if (mClosed)
            return;

        mBackgroundProcessor.submit(() -> {
            if (mClosed)
                return;

            try {
                PostProcessSettings result = mCameraSessionBridge.estimatePostProcessSettings(basicSettings, shadowsBias);

                if (mClosed)
                    return;

                mMainHandler.post(() -> {
                    if (!mClosed)
                        listener.onSettingsEstimated(result);
                });
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void measureSharpness(List<NativeCameraBuffer> buffers, SharpnessMeasuredListener listener) {
        if (mClosed)
            return;

        if(buffers.isEmpty())
            return;

        mBackgroundProcessor.submit(() -> {
            if (mClosed)
                return;

            List<Pair<NativeCameraBuffer, Double>> result = new ArrayList<>();

            for(NativeCameraBuffer buffer : buffers) {
                if (mClosed)
                    return;

                double sharpness = mCameraSessionBridge.measureSharpness(buffer.timestamp);
                result.add(new Pair<>(buffer, sharpness));
            }

            result.sort((l, r) -> l.second.compareTo(r.second));

            if (mClosed)
                return;

            mMainHandler.post(() -> {
                if (!mClosed)
                    listener.onSharpnessMeasured(result);
            });
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
        if (mClosed)
            return;

        PostProcessSettings postProcessSettings = settings.clone();

        ExecutorService p = canSkip ? mBackgroundProcessor : mQueuedBackgroundProcessor;
        p.submit(() -> {
            if (mClosed)
                return;

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

            if (mClosed)
                return;

            final Bitmap resultBitmap = preview;

            // On the main thread, let listeners know that an image is ready
            mMainHandler.post(() -> {
                if (!mClosed)
                    listener.onPreviewAvailable(buffer, resultBitmap);
            });
        });
    }
}
