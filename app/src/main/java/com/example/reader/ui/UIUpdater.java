package com.example.reader.ui;

import android.content.Context;
import android.widget.TextView;

import com.example.reader.MRZGuidanceOverlay;
import com.example.reader.detection.DocumentAlignmentDetector;
import com.example.reader.mrz.MRZProcessor;
import com.example.reader.utils.DocumentTypeDetector;

/**
 * Manages all UI updates for the camera activity.
 * Centralizes UI logic to keep detection handlers clean.
 */
public class UIUpdater {
    private final Context context;
    private final MRZGuidanceOverlay guidanceOverlay;
    private final TextView instructionLabel;
    private final TextView documentTypeLabel;
    private final TextView resultLabel;

    public UIUpdater(Context context, MRZGuidanceOverlay guidanceOverlay,
                     TextView instructionLabel, TextView documentTypeLabel,
                     TextView resultLabel) {
        this.context = context;
        this.guidanceOverlay = guidanceOverlay;
        this.instructionLabel = instructionLabel;
        this.documentTypeLabel = documentTypeLabel;
        this.resultLabel = resultLabel;
    }

    /**
     * Updates UI based on document alignment state.
     */
    public void updateAlignmentUI(DocumentAlignmentDetector.AlignmentResult result) {
        if (context == null) return;

        ((android.app.Activity) context).runOnUiThread(() -> {
            updateGuidanceOverlay(result);
            updateInstructionLabel(result);
        });
    }

    /**
     * Updates the guidance overlay visual feedback.
     */
    private void updateGuidanceOverlay(DocumentAlignmentDetector.AlignmentResult result) {
        if (guidanceOverlay != null) {
            guidanceOverlay.setAlignmentState(
                    result.documentDetected,
                    result.isAligned,
                    result.corners,
                    result.alignmentScore
            );
        }
    }

    /**
     * Updates the instruction label text and color.
     */
    private void updateInstructionLabel(DocumentAlignmentDetector.AlignmentResult result) {
        if (instructionLabel == null) return;

        instructionLabel.setText(result.message);

        // Set background color based on alignment state
        int backgroundColor;
        if (result.isAligned) {
            backgroundColor = 0x8000FF00; // Green with transparency
        } else if (result.documentDetected) {
            backgroundColor = 0x80FFFF00; // Yellow with transparency
        } else {
            backgroundColor = 0x80000000; // Dark with transparency
        }

        instructionLabel.setBackgroundColor(backgroundColor);
    }

    /**
     * Updates UI based on MRZ detection state.
     */
    public void updateDetectionUI(MRZProcessor.DetectionResult result) {
        if (context == null) return;

        ((android.app.Activity) context).runOnUiThread(() -> {
            if (result.mrzFound && result.mrzText != null) {
                showMRZDetected(result);
            } else {
                showScanning(result.lineCount);
            }
        });
    }

    /**
     * Shows MRZ detected state with progress.
     */
    private void showMRZDetected(MRZProcessor.DetectionResult result) {
        // Update document type label
        if (documentTypeLabel != null) {
            String docTypeName = DocumentTypeDetector.getDocumentTypeName(result.documentType);
            documentTypeLabel.setText(" " + docTypeName + " ");

            int color = context.getResources().getColor(android.R.color.holo_green_dark);
            documentTypeLabel.setBackgroundColor(color);
        }

        // Update result label with progress dots
        if (resultLabel != null) {
            int progress = Math.min(result.consecutiveCount, result.requiredCount);
            String dots = "●".repeat(progress) +
                    "○".repeat(result.requiredCount - progress);

            String confStr = String.format(" (%.0f%%)", result.confidence * 100);
            String text = dots + confStr + "\n" + result.mrzText;

            resultLabel.setText(text);
        }

        // Update guidance overlay detection state
        if (guidanceOverlay != null) {
            guidanceOverlay.setDetectionState(true,
                    result.consecutiveCount,
                    result.requiredCount);
        }
    }

    /**
     * Shows scanning/searching state.
     */
    private void showScanning(int lineCount) {
        // Reset consecutive detection
        if (documentTypeLabel != null) {
            documentTypeLabel.setText("Detecting document type...");

            int color = context.getResources().getColor(android.R.color.holo_blue_dark);
            documentTypeLabel.setBackgroundColor(color);
        }

        if (resultLabel != null) {
            String message;
            if (lineCount > 0) {
                message = "Partial MRZ detected (" + lineCount +
                        " line" + (lineCount > 1 ? "s" : "") + ")...";
            } else {
                message = "Scanning for MRZ...\n掃描機讀碼中...";
            }
            resultLabel.setText(message);
        }

        if (guidanceOverlay != null) {
            guidanceOverlay.setDetectionState(false, 0, 3);
        }
    }

    /**
     * Shows success state when scan is complete.
     */
    public void showSuccess() {
        if (context == null) return;

        ((android.app.Activity) context).runOnUiThread(() -> {
            if (guidanceOverlay != null) {
                guidanceOverlay.setSuccessState();
            }

            if (instructionLabel != null) {
                instructionLabel.setText("✓ Scan Complete!");
                instructionLabel.setBackgroundColor(0xFF00FF00); // Solid green
            }
        });
    }

    /**
     * Shows error state.
     */
    public void showError(String errorMessage) {
        if (context == null) return;

        ((android.app.Activity) context).runOnUiThread(() -> {
            if (instructionLabel != null) {
                instructionLabel.setText("❌ " + errorMessage);
                instructionLabel.setBackgroundColor(0xFFFF0000); // Red
            }

            if (resultLabel != null) {
                resultLabel.setText("");
            }
        });
    }

    /**
     * Shows initialization state.
     */
    public void showInitializing() {
        if (context == null) return;

        ((android.app.Activity) context).runOnUiThread(() -> {
            if (instructionLabel != null) {
                instructionLabel.setText("Initializing camera...");
                instructionLabel.setBackgroundColor(0x80000000);
            }

            if (documentTypeLabel != null) {
                documentTypeLabel.setText("Ready to scan");

                int color = context.getResources().getColor(android.R.color.darker_gray);
                documentTypeLabel.setBackgroundColor(color);
            }

            if (resultLabel != null) {
                resultLabel.setText("Position document in frame");
            }
        });
    }

    /**
     * Updates confidence visualization.
     */
    public void updateConfidence(float confidence) {
        if (context == null || resultLabel == null) return;

        ((android.app.Activity) context).runOnUiThread(() -> {
            // Could add a progress bar or color coding based on confidence
            int alpha = (int) (confidence * 255);
            int color = (0x00FF00 << 8) | (alpha << 24); // Green with alpha
            resultLabel.setTextColor(color);
        });
    }

    /**
     * Shows capture feedback (flash effect).
     */
    public void showCaptureFlash() {
        if (context == null || guidanceOverlay == null) return;

        ((android.app.Activity) context).runOnUiThread(() -> {
            guidanceOverlay.setAlpha(0.3f);
            guidanceOverlay.postDelayed(() -> {
                guidanceOverlay.setAlpha(1.0f);
            }, 100);
        });
    }

    /**
     * Resets all UI elements to initial state.
     */
    public void reset() {
        if (context == null) return;

        ((android.app.Activity) context).runOnUiThread(() -> {
            if (guidanceOverlay != null) {
//                guidanceOverlay.reset();
            }

            if (instructionLabel != null) {
                instructionLabel.setText("Position document in frame");
                instructionLabel.setBackgroundColor(0x80000000);
            }

            if (documentTypeLabel != null) {
                documentTypeLabel.setText("Scanning...");

                int color = context.getResources().getColor(android.R.color.darker_gray);
                documentTypeLabel.setBackgroundColor(color);
            }

            if (resultLabel != null) {
                resultLabel.setText("");
            }
        });
    }
}