package com.example.reader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.reader.mrz.EepMrzParser;
import com.example.reader.mrz.MrzParser;
import com.example.reader.mrz.Td3PassportParser;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final long PROCESS_INTERVAL = 500;

    // Intent extras keys
    public static final String EXTRA_DOC_NUM = "DOC_NUM";
    public static final String EXTRA_DOB = "DOB";
    public static final String EXTRA_EXPIRY = "EXPIRY";
    public static final String EXTRA_MRZ_LINES = "MRZ_LINES";
    public static final String EXTRA_DOC_TYPE = "DOC_TYPE";

    // Document type constants
    public static final String DOC_TYPE_PASSPORT = "PASSPORT";
    public static final String DOC_TYPE_EEP = "EEP";
    public static final String DOC_TYPE_UNKNOWN = "UNKNOWN";

    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private TextRecognizer recognizer;
    private long lastProcessTime = 0;
    private boolean isProcessing = false;

    private List<MrzParser> mrzParsers;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView = findViewById(R.id.viewFinder);
        cameraExecutor = Executors.newSingleThreadExecutor();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        initializeParsers();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    private void initializeParsers() {
        mrzParsers = new ArrayList<>();
        mrzParsers.add(new EepMrzParser());
        mrzParsers.add(new Td3PassportParser());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCamera() {
        final var cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        long currentTime = System.currentTimeMillis();

        if (isProcessing || currentTime - lastProcessTime < PROCESS_INTERVAL) {
            imageProxy.close();
            return;
        }

        lastProcessTime = currentTime;
        isProcessing = true;

        if (imageProxy.getImage() != null) {
            InputImage image = InputImage.fromMediaImage(
                    imageProxy.getImage(),
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            recognizer.process(image)
                    .addOnSuccessListener(this::processText)
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Text recognition failed", e);
                        isProcessing = false;
                    })
                    .addOnCompleteListener(task -> {
                        imageProxy.close();
                        isProcessing = false;
                    });
        } else {
            imageProxy.close();
            isProcessing = false;
        }
    }

    private void processText(Text text) {
        String fullText = text.getText();
        String[] lines = fullText.split("\n");

        for (String line : lines) {
            line = line.replace(" ", "").toUpperCase();

            for (MrzParser parser : mrzParsers) {
                if (parser.canParse(line)) {
                    Intent result = parser.parse(line);

                    if (result != null) {
                        String docType = parser.getDocumentType();
                        int mrzLines = getMrzLineCount(parser);
                        String docTypeCode = getDocTypeCode(parser);

                        // Add MRZ line count and document type to the result
                        result.putExtra(EXTRA_MRZ_LINES, mrzLines);
                        result.putExtra(EXTRA_DOC_TYPE, docTypeCode);

                        Log.d(TAG, "Successfully parsed " + docType + " with " + mrzLines + " MRZ lines");

                        runOnUiThread(() -> {
                            setResult(RESULT_OK, result);
                            Toast.makeText(this, "✅ " + docType + " Scanned!", Toast.LENGTH_SHORT).show();
                            finish();
                        });
                        return;
                    }
                }
            }
        }
    }

    /**
     * Determine MRZ line count based on parser type
     */
    private int getMrzLineCount(MrzParser parser) {
        if (parser instanceof Td3PassportParser) {
            return 2; // TD3 passport has 2 lines (but often referred to as "3-line" including visual zone)
            // If your TD3 parser actually reads 3 lines, change this to 3
        } else if (parser instanceof EepMrzParser) {
            return 1; // EEP has 1 MRZ line
        }
        return 0;
    }

    /**
     * Get document type code based on parser type
     */
    private String getDocTypeCode(MrzParser parser) {
        if (parser instanceof Td3PassportParser) {
            return DOC_TYPE_PASSPORT;
        } else if (parser instanceof EepMrzParser) {
            return DOC_TYPE_EEP;
        }
        return DOC_TYPE_UNKNOWN;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        recognizer.close();
    }
}