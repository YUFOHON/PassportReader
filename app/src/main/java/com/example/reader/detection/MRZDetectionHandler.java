package com.example.reader.detection;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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

import java.util.List;

public class MRZDetectionHandler {
    private static final String TAG = "MRZDetectionHandler";
    private long PROCESS_INTERVAL = 0;

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
    private Bitmap lastHighResBitmap;
    private final MRZGuidanceOverlay guidanceOverlay;
    private final PreviewView previewView;

    public MRZDetectionHandler(Context context, MRZGuidanceOverlay guidanceOverlay,
                               TextView instructionLabel, TextView documentTypeLabel,
                               TextView resultLabel, MrzParserManager mrzParserManager,
                               DocumentAlignmentDetector alignmentDetector,
                               CameraManager cameraManager,
                               long processInterval) {
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
        this.PROCESS_INTERVAL = processInterval;
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG, "✅ MRZDetectionHandler initialized");
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    public void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (mrzProcessor.hasScanned() || isCapturingHighRes) {
            imageProxy.close();
            return;
        }

        if (!shouldProcessFrame()) {
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
    }

    private void processWithAlignment(InputImage image, ImageProxy imageProxy) {
        DocumentAlignmentDetector.AlignmentResult alignmentResult =
                alignmentDetector.checkAlignment(capturedBitmap);

        uiUpdater.updateAlignmentUI(alignmentResult);

        if (alignmentResult.isAligned) {
            Log.d(TAG, "✅ Document aligned - Processing preview OCR");

            recognizer.process(image)
                    .addOnSuccessListener(text -> {
                        Log.d(TAG, "📝 Preview OCR Success - Text blocks: " + text.getTextBlocks().size());
                        processPreviewOCRResult(text);
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "❌ Preview OCR failed", e))
                    .addOnCompleteListener(task -> finalizeImageProcessing(imageProxy));
        } else {
            Log.d(TAG, "⏸️  Not aligned - Skipping OCR");
            mrzProcessor.resetDetection();
            finalizeImageProcessing(imageProxy);
        }
    }

    private void processPreviewOCRResult(Text text) {
        List<OCRProcessor.MRZCandidate> candidates = ocrProcessor.extractMRZCandidates(text,
                alignmentDetector.getPreviewView().getHeight());

        Log.d(TAG, "🔍 Preview MRZ Candidates: " + candidates.size());

        if (!candidates.isEmpty() && hasPotentialMRZ(candidates)) {
            Log.d(TAG, "🎯 Potential MRZ detected - Capturing high-res image");
            captureHighResAndProcess();
        } else {
            var result = mrzProcessor.processDetection(candidates);
            uiUpdater.updateDetectionUI(result);
        }
    }

    private boolean hasPotentialMRZ(List<OCRProcessor.MRZCandidate> candidates) {
        for (OCRProcessor.MRZCandidate candidate : candidates) {
            String cleaned = candidate.text.replaceAll("\\s", "");
            if (cleaned.length() >= 28 && cleaned.length() <= 46) {
                if (cleaned.contains("<") || cleaned.matches(".*[A-Z]{10,}.*")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void captureHighResAndProcess() {
        if (isCapturingHighRes) {
            Log.d(TAG, "⏳ Already capturing high-res, skipping");
            return;
        }

        isCapturingHighRes = true;
        Log.d(TAG, "📷 Starting high-res capture...");

        cameraManager.getImageCapture().takePicture(
                ContextCompat.getMainExecutor(context),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy highResImage) {
                        Log.d(TAG, "📷 High-res capture success: " +
                                highResImage.getWidth() + "x" + highResImage.getHeight());

                        Bitmap highRes = BitmapUtils.imageProxyToBitmap(highResImage);
                        highResImage.close();

                        if (highRes == null) {
                            Log.e(TAG, "❌ Failed to convert high-res image");
                            isCapturingHighRes = false;
                            return;
                        }

                        processHighResImage(highRes);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "❌ High-res capture failed", exception);
                        isCapturingHighRes = false;
                    }
                });
    }

    private void processHighResImage(Bitmap highRes) {
        Bitmap cropped = BitmapUtils.cropToGuidanceOverlay(highRes, guidanceOverlay, previewView);

        if (lastHighResBitmap != null) {
            lastHighResBitmap.recycle();
        }
        lastHighResBitmap = BitmapUtils.copyBitmap(cropped);

        Bitmap preprocessed = preprocessForOCR(cropped);

        InputImage ocrInput = InputImage.fromBitmap(preprocessed, 0);

        recognizer.process(ocrInput)
                .addOnSuccessListener(text -> {
                    Log.d(TAG, "📝 High-res OCR Success - Text blocks: " + text.getTextBlocks().size());
                    processHighResOCRResult(text);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ High-res OCR failed", e);
                    isCapturingHighRes = false;
                })
                .addOnCompleteListener(task -> {
                    highRes.recycle();
                    cropped.recycle();
                    preprocessed.recycle();

                    if (!mrzProcessor.hasScanned()) {
                        isCapturingHighRes = false;
                    }
                });
    }

    private void processHighResOCRResult(Text text) {
        List<OCRProcessor.MRZCandidate> candidates = ocrProcessor.extractMRZCandidates(text,
                alignmentDetector.getPreviewView().getHeight());

        Log.d(TAG, "🔍 High-res MRZ Candidates: " + candidates.size());

        var result = mrzProcessor.processDetection(candidates);

        Log.d(TAG, "🎯 Detection Result:");
        Log.d(TAG, "   ├─ Should Accept: " + result.shouldAccept());
        Log.d(TAG, "   └─ Has Valid MRZ: " + (result.getMrzInfo() != null));

        uiUpdater.updateDetectionUI(result);

        if (result.shouldAccept()) {
            handleSuccessfulScan(result);
        } else {
            Log.d(TAG, "⚠️ High-res scan declined, continuing...");
        }
    }

    public static Bitmap preprocessForOCR(Bitmap input) {
        Mat src = new Mat();
        Utils.bitmapToMat(input, src);

        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY);

        CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
        Mat enhanced = new Mat();
        clahe.apply(gray, enhanced);

        Mat sharpened = new Mat();
        Imgproc.GaussianBlur(enhanced, sharpened, new Size(0, 0), 3);
        Core.addWeighted(enhanced, 1.5, sharpened, -0.5, 0, sharpened);

        Bitmap result = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(sharpened, result);

        src.release();
        gray.release();
        enhanced.release();
        sharpened.release();

        return result;
    }

    private void handleSuccessfulScan(MRZProcessor.DetectionResult result) {
        Log.d(TAG, "✅ SUCCESSFUL SCAN");

        if (!(context instanceof Activity)) {
            Log.e(TAG, "❌ Context is not an Activity");
            return;
        }

        Activity activity = (Activity) context;

        if (lastHighResBitmap != null) {
            String savedPath = BitmapUtils.saveBitmapToFile(context, lastHighResBitmap);
            Log.d(TAG, "📷 High-res document saved: " + savedPath);
        } else {
            Log.w(TAG, "⚠️ No high-res bitmap available");
        }

        sendResultAndFinish(activity, result);
    }

    private void sendResultAndFinish(Activity activity, MRZProcessor.DetectionResult result) {
        Intent resultIntent = new Intent();

        if (result.getMrzInfo() != null) {
            var mrzInfo = result.getMrzInfo();

            resultIntent.putExtra(Constants.EXTRA_DOC_NUM, mrzInfo.documentNumber);
            resultIntent.putExtra(Constants.EXTRA_DOB, mrzInfo.dateOfBirth);
            resultIntent.putExtra(Constants.EXTRA_EXPIRY, mrzInfo.expiryDate);
            resultIntent.putExtra(Constants.EXTRA_MRZ_LINES, result.getMrzLineCount());

            if (mrzInfo.documentCode != null && !mrzInfo.documentCode.isEmpty()) {
                resultIntent.putExtra(Constants.EXTRA_DOC_TYPE, mrzInfo.documentCode);
            }

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

        uiUpdater.showSuccess();

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            activity.setResult(Activity.RESULT_OK, resultIntent);
            activity.finish();
        }, 300);
    }

    private void finalizeImageProcessing(ImageProxy imageProxy) {
        imageProxy.close();
        isProcessing = false;
        Log.d(TAG, "🏁 Frame processing complete");
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