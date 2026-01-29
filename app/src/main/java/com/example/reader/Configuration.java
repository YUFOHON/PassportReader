package com.example.reader;

import android.util.Log;

public class Configuration {

    // DocumentAlignmentDetector settings
    public int requiredConsecutiveFrames = 3;
    public long detectionCooldownMs = 50;
    public float positionTolerance = 0.15f;
    public float sizeTolerance = 0.15f;
    public float iouThreshold = 0.70f;

    // OpenCV refinement settings
    public int cannyThresholdLow = 50;
    public int cannyThresholdHigh = 150;
    public double minContourAreaRatio = 0.3;
    public double maxContourAreaRatio = 0.95;
    public double cornerEpsilonFactor = 0.02;

    // MRZDetectionHandler settings
    public long processInterval = 0;

    // Private constructor for builder pattern
    public Configuration() {}

    // Static Builder class
    public static class Builder {
        private final Configuration config = new Configuration();

        public Builder setRequiredConsecutiveFrames(int frames) {
            config.requiredConsecutiveFrames = frames;
            return this;
        }

        public Builder setDetectionCooldownMs(long ms) {
            config.detectionCooldownMs = ms;
            return this;
        }

        public Builder setPositionTolerance(float tolerance) {
            config.positionTolerance = tolerance;
            return this;
        }

        public Builder setSizeTolerance(float tolerance) {
            config.sizeTolerance = tolerance;
            return this;
        }

        public Builder setIouThreshold(float threshold) {
            config.iouThreshold = threshold;
            return this;
        }

        public Builder setCannyThresholdLow(int threshold) {
            config.cannyThresholdLow = threshold;
            return this;
        }

        public Builder setCannyThresholdHigh(int threshold) {
            config.cannyThresholdHigh = threshold;
            return this;
        }

        public Builder setMinContourAreaRatio(double ratio) {
            config.minContourAreaRatio = ratio;
            return this;
        }

        public Builder setMaxContourAreaRatio(double ratio) {
            config.maxContourAreaRatio = ratio;
            return this;
        }

        public Builder setCornerEpsilonFactor(double factor) {
            config.cornerEpsilonFactor = factor;
            return this;
        }

        public Builder setProcessInterval(long interval) {
            config.processInterval = interval;
            return this;
        }

        public Configuration build() {
            String TAG = "@@>> Configuration";
            Log.d(TAG, "Building Configuration:");
            Log.d(TAG, "  requiredConsecutiveFrames: " + config.requiredConsecutiveFrames);
            Log.d(TAG, "  detectionCooldownMs: " + config.detectionCooldownMs);
            Log.d(TAG, "  positionTolerance: " + config.positionTolerance);
            Log.d(TAG, "  sizeTolerance: " + config.sizeTolerance);
            Log.d(TAG, "  iouThreshold: " + config.iouThreshold);
            Log.d(TAG, "  processInterval: " + config.processInterval);
            Log.d(TAG, "  cannyThresholdLow: " + config.cannyThresholdLow);
            Log.d(TAG, "  cannyThresholdHigh: " + config.cannyThresholdHigh);
            Log.d(TAG, "  minContourAreaRatio: " + config.minContourAreaRatio);
            Log.d(TAG, "  maxContourAreaRatio: " + config.maxContourAreaRatio);
            Log.d(TAG, "  cornerEpsilonFactor: " + config.cornerEpsilonFactor);

            return config;
        }
    }
}