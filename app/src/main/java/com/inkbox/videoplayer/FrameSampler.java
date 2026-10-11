package com.inkbox.videoplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在后台线程用 MediaMetadataRetriever 抽取画面。
 * 只保留最新一次请求的结果，过期请求在解码前直接丢弃，避免堆积。
 */
final class FrameSampler {

    private static final String TAG = "FrameSampler";

    interface FrameListener {
        void onFrame(Bitmap frame);
    }

    interface FrameRateListener {
        void onFrameRate(float framesPerSecond);
    }

    private final Context context;
    private final FrameListener listener;
    private final int maxWidth;
    private final int maxHeight;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger mediaGeneration = new AtomicInteger();
    private final AtomicInteger latestRequest = new AtomicInteger();
    /** 只在 executor 线程中访问。 */
    private MediaMetadataRetriever retriever;

    FrameSampler(Context context, int maxWidth, int maxHeight, FrameListener listener) {
        this.context = context.getApplicationContext();
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.listener = listener;
    }

    /** 切换到新视频，同时使之前所有未完成的抽帧失效。 */
    void open(Uri uri, FrameRateListener frameRateListener) {
        final int generation = mediaGeneration.incrementAndGet();
        latestRequest.incrementAndGet();
        executor.execute(() -> {
            releaseRetriever();
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(context, uri);
                retriever = r;
                float frameRate = readFrameRate(r, uri);
                if (frameRate > 0) {
                    mainHandler.post(() -> {
                        if (generation == mediaGeneration.get()) {
                            frameRateListener.onFrameRate(frameRate);
                        }
                    });
                } else {
                    Log.i(TAG, "Video frame rate unavailable; using the one-frame-per-second fallback");
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to open video for frame sampling", e);
                release(r);
            }
        });
    }

    /** 请求 positionMs 处的画面，结果回调在主线程。 */
    void requestFrame(long positionMs, boolean closestFrame) {
        final int generation = mediaGeneration.get();
        final int request = latestRequest.incrementAndGet();
        executor.execute(() -> {
            if (generation != mediaGeneration.get() || request != latestRequest.get() || retriever == null) {
                return;
            }
            final Bitmap frame = decode(positionMs, closestFrame);
            if (frame == null) {
                return; // 抽帧失败时保持原画面
            }
            mainHandler.post(() -> {
                if (generation == mediaGeneration.get() && request == latestRequest.get()) {
                    listener.onFrame(frame);
                }
            });
        });
    }

    void close() {
        mediaGeneration.incrementAndGet();
        latestRequest.incrementAndGet();
        executor.execute(this::releaseRetriever);
        executor.shutdown();
    }

    private Bitmap decode(long positionMs, boolean closestFrame) {
        try {
            int option = closestFrame
                    ? MediaMetadataRetriever.OPTION_CLOSEST
                    : MediaMetadataRetriever.OPTION_CLOSEST_SYNC;
            return retriever.getScaledFrameAtTime(positionMs * 1000L, option, maxWidth, maxHeight);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to extract frame at " + positionMs + " ms", e);
            return null;
        }
    }

    private float readFrameRate(MediaMetadataRetriever source, Uri uri) {
        String metadata = source.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
        if (metadata != null) {
            try {
                float frameRate = Float.parseFloat(metadata);
                if (frameRate > 0 && Float.isFinite(frameRate)) {
                    return frameRate;
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid video frame-rate metadata: " + metadata, e);
            }
        }

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.containsKey(MediaFormat.KEY_MIME)
                        && format.getString(MediaFormat.KEY_MIME).startsWith("video/")
                        && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    int frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                    if (frameRate > 0) {
                        return frameRate;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to read video frame rate from media tracks", e);
        } finally {
            extractor.release();
        }
        return -1;
    }

    private void releaseRetriever() {
        if (retriever != null) {
            MediaMetadataRetriever current = retriever;
            retriever = null;
            release(current);
        }
    }

    private static void release(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (IOException e) {
            Log.w(TAG, "Failed to release media retriever", e);
        }
    }
}
