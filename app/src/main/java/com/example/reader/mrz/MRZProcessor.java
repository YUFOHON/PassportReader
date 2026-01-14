package com.example.reader.mrz;

import android.content.Intent;
import android.util.Log;

import com.example.reader.ocr.OCRProcessor;
import com.example.reader.utils.DocumentTypeDetector;
import com.example.reader.utils.MRZCleaner;

import java.util.List;
import java.util.stream.Collectors;

public class MRZProcessor {
    private static final String TAG = "MRZProcessor";
    private static final int REQUIRED_CONSECUTIVE_DETECTIONS = 3;
    private static final float HIGH_CONFIDENCE_THRESHOLD = 0.7f;

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

        Log.d("MRZProcessor", "Detected Document Type: " + docType +
                ", Extracted MRZ: " + extractedMRZ +
                ", Avg Confidence: " + avgConfidence);

        if (extractedMRZ == null) {
            return DetectionResult.partial(candidates.size());
        }

        updateDetectionState(extractedMRZ, docType, avgConfidence);

        boolean shouldAccept = !hasScanned && shouldAcceptResult(avgConfidence);
        if (shouldAccept) {
            hasScanned = true;
            // Parse the MRZ using MrzParserManager
            MRZInfo mrzInfo = parseMRZ(extractedMRZ, docType);

            if (mrzInfo == null) {
                Log.w(TAG, "MRZ parsing returned null info");
                return new DetectionResult(false, candidates.size(), extractedMRZ,
                        detectedDocumentType, lastConfidence, consecutiveDetectionCount,
                        REQUIRED_CONSECUTIVE_DETECTIONS, false, mrzInfo);
            } else {
                Log.d(TAG, "MRZ parsed successfully: DocNum=" + mrzInfo.documentNumber);
            }

            return new DetectionResult(true, candidates.size(), extractedMRZ,
                    detectedDocumentType, lastConfidence, consecutiveDetectionCount,
                    REQUIRED_CONSECUTIVE_DETECTIONS, shouldAccept, mrzInfo);
        }

        return new DetectionResult(true, candidates.size(), extractedMRZ,
                detectedDocumentType, lastConfidence, consecutiveDetectionCount,
                REQUIRED_CONSECUTIVE_DETECTIONS, shouldAccept, null);
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

    /**
     * Parse MRZ text using the MrzParserManager
     */
    private MRZInfo parseMRZ(String mrzText, String docType) {
        try {
            Log.d(TAG, "Parsing MRZ with type hint: " + docType);
            Intent parsedIntent = parserManager.parseMrz(mrzText, docType);

            if (parsedIntent != null) {
                // Extract data from Intent
                String documentNumber = parsedIntent.getStringExtra("DOC_NUM");
                String dateOfBirth = parsedIntent.getStringExtra("DOB");
                String expiryDate = parsedIntent.getStringExtra("EXPIRY");
                String givenNames = parsedIntent.getStringExtra("FIRST_NAME");
                String surname = parsedIntent.getStringExtra("LAST_NAME");
                String nationality = parsedIntent.getStringExtra("NATIONALITY");
                String issuingCountry = parsedIntent.getStringExtra("ISSUING_COUNTRY");
                String sex = parsedIntent.getStringExtra("SEX");
                String documentCode = parsedIntent.getStringExtra("DOC_TYPE");

                Log.d(TAG, "Successfully parsed MRZ - DocNum: " + documentNumber +
                        ", Type: " + documentCode);

                return new MRZInfo(
                        documentNumber,
                        dateOfBirth,
                        expiryDate,
                        givenNames,
                        surname,
                        nationality,
                        issuingCountry,
                        sex,
                        documentCode
                );
            } else {
                Log.w(TAG, "Parser returned null result");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing MRZ", e);
        }
        return null;
    }

    public void resetDetection() {
        consecutiveDetectionCount = 0;
        lastStableMRZ = null;
    }

    public boolean hasScanned() {
        return hasScanned;
    }

    /**
     * Structured MRZ information extracted from Intent
     */
    public static class MRZInfo {
        public final String documentNumber;
        public final String dateOfBirth;
        public final String expiryDate;
        public final String givenNames;
        public final String surname;
        public final String nationality;
        public final String issuingCountry;
        public final String sex;
        public final String documentCode;

        public MRZInfo(String documentNumber, String dateOfBirth, String expiryDate,
                       String givenNames, String surname, String nationality,
                       String issuingCountry, String sex, String documentCode) {
            this.documentNumber = documentNumber;
            this.dateOfBirth = dateOfBirth;
            this.expiryDate = expiryDate;
            this.givenNames = givenNames;
            this.surname = surname;
            this.nationality = nationality;
            this.issuingCountry = issuingCountry;
            this.sex = sex;
            this.documentCode = documentCode;
        }
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
        private final MRZInfo mrzInfo;

        DetectionResult(boolean mrzFound, int lineCount, String mrzText,
                        String documentType, float confidence,
                        int consecutiveCount, int requiredCount, boolean shouldAccept,
                        MRZInfo mrzInfo) {
            this.mrzFound = mrzFound;
            this.lineCount = lineCount;
            this.mrzText = mrzText;
            this.documentType = documentType;
            this.confidence = confidence;
            this.consecutiveCount = consecutiveCount;
            this.requiredCount = requiredCount;
            this.shouldAccept = shouldAccept;
            this.mrzInfo = mrzInfo;
        }

        public boolean shouldAccept() {
            return shouldAccept;
        }

        /**
         * Get the parsed MRZ information
         */
        public MRZInfo getMrzInfo() {
            return mrzInfo;
        }

        /**
         * Get the number of MRZ lines detected
         */
        public int getMrzLineCount() {
            return lineCount;
        }

        public static DetectionResult noDetection() {
            return new DetectionResult(false, 0, null, null, 0f, 0, 0, false, null);
        }

        public static DetectionResult partial(int lineCount) {
            return new DetectionResult(false, lineCount, null, null, 0f, 0, 0, false, null);
        }
    }
}