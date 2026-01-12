package com.example.reader.detection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.reader.MRZGuidanceOverlay;
import com.example.reader.camera.CameraManager;
import com.example.reader.mrz.MRZProcessor;
import com.example.reader.mrz.MrzParserManager;
import com.example.reader.ocr.OCRProcessor;
import com.example.reader.ui.UIUpdater;
import com.example.reader.utils.BitmapUtils;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class MRZDetectionHandler {
    private static final String TAG = "MRZDetectionHandler";
    private static final long PROCESS_INTERVAL = 250;

    private final Context context;
    private final TextRecognizer recognizer;
    private final OCRProcessor ocrProcessor;
    private final MRZProcessor mrzProcessor;
    private final UIUpdater uiUpdater;
    private final DocumentAlignmentDetector alignmentDetector;
    private final CameraManager cameraManager;

    private long lastProcessTime = 0;
    private volatile boolean isProcessing = false;
    private int frameCount = 0;
    private Bitmap capturedBitmap;
    private final MRZGuidanceOverlay guidanceOverlay;
    private final PreviewView previewView;

    public MRZDetectionHandler(Context context, MRZGuidanceOverlay guidanceOverlay,
                               TextView instructionLabel, TextView documentTypeLabel,
                               TextView resultLabel, MrzParserManager mrzParserManager,
                               DocumentAlignmentDetector alignmentDetector,
                               CameraManager cameraManager) {
        this.context = context;
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        this.alignmentDetector = alignmentDetector;
        this.guidanceOverlay = guidanceOverlay;  // Store reference
        this.previewView = alignmentDetector.getPreviewView();
        this.ocrProcessor = new OCRProcessor();
        this.mrzProcessor = new MRZProcessor(mrzParserManager);
        this.uiUpdater = new UIUpdater(context, guidanceOverlay, instructionLabel,
                documentTypeLabel, resultLabel);
        this.cameraManager = cameraManager;

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG, "✅ MRZDetectionHandler initialized");
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    public void analyzeImage(@NonNull ImageProxy imageProxy) {
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();


        if (mrzProcessor.hasScanned() || !shouldProcessFrame()) {
            imageProxy.close();
            return;
        }

        frameCount++;
        lastProcessTime = System.currentTimeMillis();
        isProcessing = true;

        Log.d(TAG, "📸 Frame #" + frameCount + " - Starting analysis");
        processImageProxy(imageProxy);
    }

    private boolean shouldProcessFrame() {
        long currentTime = System.currentTimeMillis();
        return !isProcessing && (currentTime - lastProcessTime >= PROCESS_INTERVAL);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processImageProxy(ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) {
            Log.e(TAG, "❌ ImageProxy has null image");
            imageProxy.close();
            isProcessing = false;
            return;
        }

        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        int imageWidth = imageProxy.getWidth();
        int imageHeight = imageProxy.getHeight();

        Log.d(TAG, "📊 ImageProxy Info:");
        Log.d(TAG, "   ├─ Rotation: " + rotationDegrees + "°");
        Log.d(TAG, "   ├─ Original Size: " + imageWidth + "x" + imageHeight);

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                rotationDegrees
        );

        // Convert to bitmap - BitmapUtils.inputImageToBitmap already applies rotation!
        Bitmap bitmap = BitmapUtils.inputImageToBitmap(image, imageProxy);

        if (bitmap != null) {
            Log.d(TAG, "🖼️  Bitmap created: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            // DON'T rotate again - inputImageToBitmap already handled it!
            // The bitmap should now be in the correct orientation

            updateCapturedBitmap(bitmap);

            // Pass 0 for rotation since bitmap is already rotated
            processWithAlignment(image, imageProxy);
        } else {
            Log.e(TAG, "❌ Failed to convert to bitmap");
            imageProxy.close();
            isProcessing = false;
        }
    }

    /**
     * Rotates bitmap to match device orientation
     */
    private Bitmap rotateBitmap(Bitmap source, int rotationDegrees) {
        if (rotationDegrees == 0) {
            Log.d(TAG, "⏭️  No rotation needed (0°)");
            return source;
        }

        Log.d(TAG, "🔄 Rotating bitmap by " + rotationDegrees + "°");
        Log.d(TAG, "   Source: " + source.getWidth() + "x" + source.getHeight());

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);

        try {
            Bitmap rotated = Bitmap.createBitmap(
                    source, 0, 0,
                    source.getWidth(), source.getHeight(),
                    matrix, true
            );

            Log.d(TAG, "   ✅ Result: " + rotated.getWidth() + "x" + rotated.getHeight());

            return rotated;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "❌ Out of memory rotating bitmap", e);
            return source;
        }
    }

    private void updateCapturedBitmap(Bitmap newBitmap) {
        if (capturedBitmap != null && capturedBitmap != newBitmap) {
            capturedBitmap.recycle();
        }
        capturedBitmap = newBitmap;
        Log.d(TAG, "💾 Captured bitmap updated");
    }

    private void processWithAlignment(InputImage image, ImageProxy imageProxy) {
        Log.d(TAG, "🎯 Checking alignment...");

        DocumentAlignmentDetector.AlignmentResult alignmentResult =
                alignmentDetector.checkAlignment(capturedBitmap);

        Log.d(TAG, "📍 Alignment Result:");
        Log.d(TAG, "   ├─ Document Detected: " + alignmentResult.documentDetected);
        Log.d(TAG, "   ├─ Is Aligned: " + alignmentResult.isAligned);
        Log.d(TAG, "   ├─ Score: " + String.format("%.2f", alignmentResult.alignmentScore));
        Log.d(TAG, "   ├─ Message: " + alignmentResult.message);
        Log.d(TAG, "   └─ Corners: " + (alignmentResult.corners != null ? alignmentResult.corners.length : "null"));

        uiUpdater.updateAlignmentUI(alignmentResult);

        if (alignmentResult.isAligned) {
            Log.d(TAG, "✅ Document aligned - Processing OCR");
            recognizer.process(image)
                    .addOnSuccessListener(text -> {
                        Log.d(TAG, "📝 OCR Success - Text blocks: " + text.getTextBlocks().size());
                        processOCRResult(text);
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "❌ OCR failed", e))
                    .addOnCompleteListener(task -> finalizeImageProcessing(imageProxy));


        } else {
            Log.d(TAG, "⏸️  Not aligned - Skipping OCR");
            mrzProcessor.resetDetection();
            finalizeImageProcessing(imageProxy);
        }
    }

    private void processOCRResult(Text text) {
        var candidates = ocrProcessor.extractMRZCandidates(text,
                alignmentDetector.getPreviewView().getHeight());

        Log.d(TAG, "🔍 MRZ Candidates extracted: " + candidates.size());

        var result = mrzProcessor.processDetection(candidates);

        Log.d(TAG, "🎯 Detection Result:");
        Log.d(TAG, "   ├─ Should Accept: " + result.shouldAccept());
        Log.d(TAG, "   └─ Has Valid MRZ: " );

        uiUpdater.updateDetectionUI(result);

        if (result.shouldAccept()) {
            handleSuccessfulScan(result);
        }
    }

    private void handleSuccessfulScan(MRZProcessor.DetectionResult result) {
        Log.d(TAG, "✅ SUCCESSFUL SCAN - Capturing high-res image");

        ImageCapture imageCapture = cameraManager.getImageCapture();
        if (imageCapture == null) {
            Log.e(TAG, "❌ ImageCapture not available");
            return;
        }

        imageCapture.takePicture(ContextCompat.getMainExecutor(context),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                        Bitmap highResBitmap = BitmapUtils.imageProxyToBitmap(image);
                        image.close();

                        if (highResBitmap != null) {
                            Bitmap cropped = BitmapUtils.cropToGuidanceOverlay(
                                    highResBitmap, guidanceOverlay, previewView);

                            String savedPath = BitmapUtils.saveBitmapToFile(context, cropped);
                            Log.d(TAG, "📷 High-res document saved: " + savedPath);

                            BitmapUtils.recycleBitmap(highResBitmap);
                            if (cropped != highResBitmap) {
                                BitmapUtils.recycleBitmap(cropped);
                            }
                        }

                        uiUpdater.showSuccess();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Log.e(TAG, "❌ High-res capture failed", e);
                        // Fallback to preview bitmap
                        if (capturedBitmap != null) {
                            Bitmap cropped = BitmapUtils.cropToGuidanceOverlay(
                                    BitmapUtils.copyBitmap(capturedBitmap), guidanceOverlay, previewView);
                            BitmapUtils.saveBitmapToFile(context, cropped);
                            BitmapUtils.recycleBitmap(cropped);
                        }
                        uiUpdater.showSuccess();
                    }
                });
    }
    private void finalizeImageProcessing(ImageProxy imageProxy) {
        imageProxy.close();
        isProcessing = false;
        Log.d(TAG, "🏁 Frame processing complete\n");
    }

    public Bitmap getCapturedBitmap() {
        return capturedBitmap;
    }

    public void cleanup() {
        Log.d(TAG, "🧹 Cleaning up resources...");
        recognizer.close();
        if (capturedBitmap != null) {
            capturedBitmap.recycle();
            capturedBitmap = null;
        }
        Log.d(TAG, "✅ Cleanup complete");
    }
}