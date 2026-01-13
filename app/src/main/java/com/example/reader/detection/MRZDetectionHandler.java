package com.example.reader.detection;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import com.example.reader.utils.Constants;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

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
    private volatile boolean isCapturingHighRes = false;
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
        this.guidanceOverlay = guidanceOverlay;
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
        // Skip if already scanned or capturing
        if (mrzProcessor.hasScanned() || isCapturingHighRes || !shouldProcessFrame()) {
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

        Bitmap bitmap = BitmapUtils.inputImageToBitmap(image, imageProxy);

        if (bitmap != null) {
            Log.d(TAG, "🖼️  Bitmap created: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            updateCapturedBitmap(bitmap);
            processWithAlignment(image, imageProxy);
        } else {
            Log.e(TAG, "❌ Failed to convert to bitmap");
            imageProxy.close();
            isProcessing = false;
        }
    }

    private void updateCapturedBitmap(Bitmap newBitmap) {
        if (capturedBitmap != null && capturedBitmap != newBitmap) {
            capturedBitmap.recycle();
        }
        capturedBitmap = newBitmap;
        Log.d(TAG, "💾 Captured bitmap updated");
    }

//    private void processWithAlignment(InputImage image, ImageProxy imageProxy) {
//        Log.d(TAG, "🎯 Checking alignment...");
//
//        DocumentAlignmentDetector.AlignmentResult alignmentResult =
//                alignmentDetector.checkAlignment(capturedBitmap);
//
//        Log.d(TAG, "📍 Alignment Result:");
//        Log.d(TAG, "   ├─ Document Detected: " + alignmentResult.documentDetected);
//        Log.d(TAG, "   ├─ Is Aligned: " + alignmentResult.isAligned);
//        Log.d(TAG, "   ├─ Score: " + String.format("%.2f", alignmentResult.alignmentScore));
//        Log.d(TAG, "   ├─ Message: " + alignmentResult.message);
//        Log.d(TAG, "   └─ Corners: " + (alignmentResult.corners != null ? alignmentResult.corners.length : "null"));
//
//        uiUpdater.updateAlignmentUI(alignmentResult);
//
//        if (alignmentResult.isAligned) {
//            Log.d(TAG, "✅ Document aligned - Processing OCR");
//            recognizer.process(image)
//                    .addOnSuccessListener(text -> {
//                        Log.d(TAG, "📝 OCR Success - Text blocks: " + text.getTextBlocks().size());
//                        processOCRResult(text);
//                    })
//                    .addOnFailureListener(e -> Log.e(TAG, "❌ OCR failed", e))
//                    .addOnCompleteListener(task -> finalizeImageProcessing(imageProxy));
//        } else {
//            Log.d(TAG, "⏸️  Not aligned - Skipping OCR");
//            mrzProcessor.resetDetection();
//            finalizeImageProcessing(imageProxy);
//        }
//    }
private Bitmap lastHighResBitmap; // Add this field

    private void processWithAlignment(InputImage image, ImageProxy imageProxy) {
        DocumentAlignmentDetector.AlignmentResult alignmentResult =
                alignmentDetector.checkAlignment(capturedBitmap);

        uiUpdater.updateAlignmentUI(alignmentResult);

        if (alignmentResult.isAligned && !isCapturingHighRes) {
            isCapturingHighRes = true;

            imageProxy.close();
            isProcessing = false;

            cameraManager.getImageCapture().takePicture(
                    ContextCompat.getMainExecutor(context),
                    new ImageCapture.OnImageCapturedCallback() {
                        @Override
                        public void onCaptureSuccess(@NonNull ImageProxy highResImage) {
                            Bitmap highRes = BitmapUtils.imageProxyToBitmap(highResImage);
                            highResImage.close();

                            if (highRes == null) {
                                isCapturingHighRes = false;
                                return;
                            }

                            Bitmap cropped = BitmapUtils.cropToGuidanceOverlay(highRes, guidanceOverlay, previewView);

                            // Store the high-res cropped bitmap for later saving
                            if (lastHighResBitmap != null) {
                                lastHighResBitmap.recycle();
                            }
                            lastHighResBitmap = BitmapUtils.copyBitmap(cropped);

                            Bitmap preprocessed = preprocessForOCR(cropped);

                            InputImage ocrInput = InputImage.fromBitmap(preprocessed, 0);
                            recognizer.process(ocrInput)
                                    .addOnSuccessListener(MRZDetectionHandler.this::processOCRResult)
                                    .addOnFailureListener(e -> {
                                        Log.e(TAG, "❌ OCR failed", e);
                                    })
                                    .addOnCompleteListener(task -> {
                                        if (!mrzProcessor.hasScanned()) {
                                            isCapturingHighRes = false;
                                        }
                                        // Cleanup
                                        highRes.recycle();
                                        cropped.recycle();
                                        preprocessed.recycle();
                                    });
                        }

                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            Log.e(TAG, "❌ High-res capture failed", exception);
                            isCapturingHighRes = false;
                        }
                    });
        } else {
            finalizeImageProcessing(imageProxy);
        }
    }
    public static Bitmap preprocessForOCR(Bitmap input) {
        Mat src = new Mat();
        Utils.bitmapToMat(input, src);

        // Convert to grayscale
        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY);

        // Apply CLAHE for contrast enhancement
        CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
        Mat enhanced = new Mat();
        clahe.apply(gray, enhanced);

        // Sharpen
        Mat sharpened = new Mat();
        Imgproc.GaussianBlur(enhanced, sharpened, new Size(0, 0), 3);
        Core.addWeighted(enhanced, 1.5, sharpened, -0.5, 0, sharpened);

        // Adaptive threshold for binarization (optional, test both)
        // Imgproc.adaptiveThreshold(sharpened, sharpened, 255,
        //     Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 11, 2);

        Bitmap result = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(sharpened, result);

        // Cleanup
        src.release();
        gray.release();
        enhanced.release();
        sharpened.release();

        return result;
    }

    private void processOCRResult(Text text) {


        var candidates = ocrProcessor.extractMRZCandidates(text,
                alignmentDetector.getPreviewView().getHeight());

        Log.d(TAG, "🔍 MRZ Candidates extracted: " + candidates.size());

        var result = mrzProcessor.processDetection(candidates);

        Log.d(TAG, "🎯 Detection Result:");
        Log.d(TAG, "   ├─ Should Accept: " + result.shouldAccept());
        Log.d(TAG, "   └─ Has Valid MRZ: " + (result.getMrzInfo() != null));

        uiUpdater.updateDetectionUI(result);

        if (result.shouldAccept()) {
            handleSuccessfulScan(result);
        }else {

            Log.d(TAG, "Declineing result, continuing scanning.");

        }
    }

    private void handleSuccessfulScan(MRZProcessor.DetectionResult result) {
        Log.d(TAG, "✅ SUCCESSFUL SCAN");

        if (!(context instanceof Activity)) {
            Log.e(TAG, "❌ Context is not an Activity");
            return;
        }

        Activity activity = (Activity) context;

        // Save the already-captured high-res bitmap
        if (lastHighResBitmap != null) {
            String savedPath = BitmapUtils.saveBitmapToFile(context, lastHighResBitmap);
            Log.d(TAG, "📷 High-res document saved: " + savedPath);
        } else {
            Log.w(TAG, "⚠️ No high-res bitmap available");
        }

        sendResultAndFinish(activity, result);
    }
    /**
     * Send result data to activity and finish
     */
    private void sendResultAndFinish(Activity activity, MRZProcessor.DetectionResult result) {
        Intent resultIntent = new Intent();

        // Extract MRZ data from result
        if (result.getMrzInfo() != null) {
            var mrzInfo = result.getMrzInfo();

            // Add basic MRZ data
            resultIntent.putExtra(Constants.EXTRA_DOC_NUM, mrzInfo.documentNumber);
            resultIntent.putExtra(Constants.EXTRA_DOB, mrzInfo.dateOfBirth);
            resultIntent.putExtra(Constants.EXTRA_EXPIRY, mrzInfo.expiryDate);

            // Add MRZ line count
            resultIntent.putExtra(Constants.EXTRA_MRZ_LINES, result.getMrzLineCount());

            // Add document type code if available
            if (mrzInfo.documentCode != null && !mrzInfo.documentCode.isEmpty()) {
                resultIntent.putExtra(Constants.EXTRA_DOC_TYPE, mrzInfo.documentCode);
            }

            // Add additional data
            resultIntent.putExtra("FIRST_NAME", mrzInfo.givenNames);
            resultIntent.putExtra("LAST_NAME", mrzInfo.surname);
            resultIntent.putExtra("NATIONALITY", mrzInfo.nationality);
            resultIntent.putExtra("ISSUING_COUNTRY", mrzInfo.issuingCountry);
            resultIntent.putExtra("GENDER", mrzInfo.sex);

            Log.d(TAG, "📤 Sending result to activity:");
            Log.d(TAG, "   ├─ Document Number: " + mrzInfo.documentNumber);
            Log.d(TAG, "   ├─ DOB: " + mrzInfo.dateOfBirth);
            Log.d(TAG, "   ├─ Expiry: " + mrzInfo.expiryDate);
            Log.d(TAG, "   ├─ MRZ Lines: " + result.getMrzLineCount());
            Log.d(TAG, "   └─ Doc Type: " + mrzInfo.documentCode);
        }

        // Show success UI
        uiUpdater.showSuccess();

        // Small delay to ensure UI feedback is visible
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            activity.setResult(Activity.RESULT_OK, resultIntent);
            activity.finish();
        }, 300); // 300ms delay for visual feedback
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
        if (lastHighResBitmap != null) {
            lastHighResBitmap.recycle();
            lastHighResBitmap = null;
        }
        Log.d(TAG, "✅ Cleanup complete");
    }
}