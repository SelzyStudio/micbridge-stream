package com.limelight.ui;

/** A final display stage that follows the codec's intermediate output surface. */
public interface FramePresentation {
    void runAfterNextVideoFrameDraw(Runnable callback);
    void cancelPendingFramePresentation();
}
