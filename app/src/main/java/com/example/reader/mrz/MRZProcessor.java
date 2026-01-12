package com.example.reader.mrz;

import com.example.reader.ocr.OCRProcessor;
import com.example.reader.utils.DocumentTypeDetector;
import com.example.reader.utils.MRZCleaner;

import java.util.List;
import java.util.stream.Collectors;

public class MRZProcessor {
    private static final int REQUIRED_CONSECUTIVE_DETECTIONS = 3;
    private static final float HIGH_CONFIDENCE_THRESHOLD = 0.75f;

    private final MrzParserManager parserManager;

    private int consecutiveDetectionCount = 0;
    private String lastStableMRZ = null;
    private String detectedDocumentType = null;
    private float lastConfidence = 0f;
    private boolean hasScanned = false;

    public MRZProcessor(MrzParserManager parserManager) {
        this.parserManager = parserManager;
    }

    public DetectionResult processDetection(List<OCRProcessor.MRZCandidate> candidates) {
        if (candidates.isEmpty()) {
            return DetectionResult.noDetection();
        }

        List<String> candidateTexts = candidates.stream()
                .map(c -> c.text)
                .collect(Collectors.toList());

        float avgConfidence = (float) candidates.stream()
                .mapToDouble(c -> c.confidence)
                .average()
                .orElse(0.5);

        String docType = DocumentTypeDetector.detect(candidateTexts);
        String extractedMRZ = MRZCleaner.extractAndClean(candidateTexts, docType);

        if (extractedMRZ == null) {
            return DetectionResult.partial(candidates.size());
        }

        updateDetectionState(extractedMRZ, docType, avgConfidence);

        boolean shouldAccept = !hasScanned && shouldAcceptResult(avgConfidence);
        if (shouldAccept) {
            hasScanned = true;
        }

        return new DetectionResult(true, candidates.size(), extractedMRZ,
                detectedDocumentType, lastConfidence, consecutiveDetectionCount,
                REQUIRED_CONSECUTIVE_DETECTIONS, shouldAccept);
    }

    private void updateDetectionState(String mrzText, String docType, float confidence) {
        detectedDocumentType = docType;
        lastConfidence = confidence;

        if (lastStableMRZ != null && MRZCleaner.areSimilar(lastStableMRZ, mrzText)) {
            consecutiveDetectionCount++;
        } else {
            lastStableMRZ = mrzText;
            consecutiveDetectionCount = 1;
        }
    }

    private boolean shouldAcceptResult(float confidence) {
        return consecutiveDetectionCount >= REQUIRED_CONSECUTIVE_DETECTIONS ||
                (consecutiveDetectionCount >= 1 && confidence >= HIGH_CONFIDENCE_THRESHOLD);
    }

    public void resetDetection() {
        consecutiveDetectionCount = 0;
        lastStableMRZ = null;
    }

    public boolean hasScanned() {
        return hasScanned;
    }

    public static class DetectionResult {
        public final boolean mrzFound;
        public final int lineCount;
        public final String mrzText;
        public final String documentType;
        public final float confidence;
        public final int consecutiveCount;
        public final int requiredCount;
        private final boolean shouldAccept;

        DetectionResult(boolean mrzFound, int lineCount, String mrzText,
                        String documentType, float confidence,
                        int consecutiveCount, int requiredCount, boolean shouldAccept) {
            this.mrzFound = mrzFound;
            this.lineCount = lineCount;
            this.mrzText = mrzText;
            this.documentType = documentType;
            this.confidence = confidence;
            this.consecutiveCount = consecutiveCount;
            this.requiredCount = requiredCount;
            this.shouldAccept = shouldAccept;
        }

        public boolean shouldAccept() {
            return shouldAccept;
        }

        public static DetectionResult noDetection() {
            return new DetectionResult(false, 0, null, null, 0f, 0, 0, false);
        }

        public static DetectionResult partial(int lineCount) {
            return new DetectionResult(false, lineCount, null, null, 0f, 0, 0, false);
        }
    }
}