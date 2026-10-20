package com.limelight.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Holds one in-memory video frame while its transport or decoder is being replaced. */
public class StreamContinuityOverlay {
    static final int CAPTURE_TIMEOUT_MS = 250;
    static final int MAX_PIXELS = 2_000_000;

    interface CopyCompletion {
        void onPixelCopyFinished(int result);
    }

    interface FrameCopier {
        void request(SurfaceView source, Bitmap destination, CopyCompletion listener,
                     Handler handler);
    }

    // Keep API24 types out of the compatibility path's method signatures and lambdas.
    private static class Api24Copier {
        static void request(SurfaceView source, Bitmap destination, CopyCompletion listener, Handler handler) {
            PixelCopy.request(source, destination, listener::onPixelCopyFinished, handler);
        }
    }

    private final FrameLayout container;
    private final ImageView image;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final FrameCopier copier;
    private long generation;
    private boolean awaitingFreshFrame;
    private Runnable pendingCompletion;

    public StreamContinuityOverlay(Context context, FrameLayout container) {
        this(context, container, Build.VERSION.SDK_INT >= 24 ? Api24Copier::request :
                (source, destination, listener, handler) -> listener.onPixelCopyFinished(-1));
    }

    StreamContinuityOverlay(Context context, FrameLayout container, FrameCopier copier) {
        this.container = container;
        this.copier = copier;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setVisibility(View.GONE);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setFocusable(false);
        image.setClickable(false);
        container.addView(image, new FrameLayout.LayoutParams(1, 1));
    }

    public boolean isHoldingFrame() {
        return image.getVisibility() == View.VISIBLE && image.getDrawable() != null;
    }

    public boolean isAwaitingFreshFrame() {
        return awaitingFreshFrame;
    }

    public void markAwaitingFreshFrame() {
        awaitingFreshFrame = true;
    }

    /** Must be called on the main thread, before stopping the decoder that owns source. */
    public void captureAndHold(SurfaceView source, Runnable afterCapture) {
        awaitingFreshFrame = true;
        if (pendingCompletion != null) {
            Runnable previous = pendingCompletion;
            pendingCompletion = () -> {
                previous.run();
                afterCapture.run();
            };
            return;
        }
        final long requestGeneration = ++generation;
        if (isHoldingFrame() || source == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                source.getWidth() <= 0 || source.getHeight() <= 0 ||
                !source.getHolder().getSurface().isValid()) {
            afterCapture.run();
            return;
        }

        // PixelCopy scales the buffer directly; avoid ever allocating a full 4K screenshot.
        double ratio = Math.min(1.0, Math.sqrt((double) MAX_PIXELS /
                ((long) source.getWidth() * source.getHeight())));
        final Bitmap frame;
        try {
            frame = Bitmap.createBitmap(Math.max(1, (int) (source.getWidth() * ratio)),
                    Math.max(1, (int) (source.getHeight() * ratio)), Bitmap.Config.ARGB_8888);
        } catch (IllegalArgumentException | OutOfMemoryError allocationFailure) {
            afterCapture.run();
            return;
        }

        pendingCompletion = afterCapture;
        Runnable timeout = () -> {
            if (generation == requestGeneration) {
                generation++;
                finishPendingCapture();
            }
        };
        mainHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS);
        try {
            copier.request(source, frame, result -> {
                if (requestGeneration != generation) {
                    return;
                }
                mainHandler.removeCallbacks(timeout);
                if (result == PixelCopy.SUCCESS && awaitingFreshFrame) {
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(source.getWidth(), source.getHeight());
                    params.leftMargin = source.getLeft();
                    params.topMargin = source.getTop();
                    image.setLayoutParams(params);
                    image.setPivotX(source.getPivotX());
                    image.setPivotY(source.getPivotY());
                    image.setScaleX(source.getScaleX());
                    image.setScaleY(source.getScaleY());
                    image.setTranslationX(source.getTranslationX());
                    image.setTranslationY(source.getTranslationY());
                    image.setRotation(source.getRotation());
                    image.setImageBitmap(frame);
                    image.setVisibility(View.VISIBLE);
                    image.bringToFront();
                }
                finishPendingCapture();
            }, mainHandler);
        } catch (IllegalArgumentException | IllegalStateException copyFailure) {
            mainHandler.removeCallbacks(timeout);
            finishPendingCapture();
        }
    }

    /** A newly presented frame (or explicit session end) is the only reason to release the cover. */
    public void release() {
        generation++;
        awaitingFreshFrame = false;
        image.setVisibility(View.GONE);
        // Do not recycle: a PixelCopy operation or the render thread may still reference this bitmap.
        image.setImageDrawable(null);
        finishPendingCapture();
    }

    private void finishPendingCapture() {
        Runnable completion = pendingCompletion;
        pendingCompletion = null;
        if (completion != null) {
            completion.run();
        }
    }
}
