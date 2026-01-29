package com.example.reader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.reader.camera.CameraManager;
import com.example.reader.detection.DocumentAlignmentDetector;
import com.example.reader.detection.MRZDetectionHandler;
import com.example.reader.mrz.MrzParserManager;
import com.example.reader.utils.BitmapUtils;
import com.example.reader.utils.Constants;

import org.opencv.android.OpenCVLoader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity@@>>";
    private static final int CAMERA_PERMISSION_REQUEST = 10;

    // UI Components
    private MRZGuidanceOverlay guidanceOverlay;
    private TextView instructionLabel;
    private PreviewView previewView;
    private TextView documentTypeLabel;
    private TextView resultLabel;

    // Managers
    private CameraManager cameraManager;
    private MRZDetectionHandler detectionHandler;
    private DocumentAlignmentDetector alignmentDetector;
    private MrzParserManager mrzParserManager;
    private ExecutorService cameraExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState)     {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        initializeOpenCV();
        initializeViews();
        initializeManagers();

        previewView.post(() -> {
            alignmentDetector.updateCachedValues();
            Log.d(TAG, "📐 Initial cache update triggered");
        });

        // Also add a layout listener for continuous updates
        previewView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            alignmentDetector.updateCachedValues();
        });

        requestCameraPermission();
    }

    private void initializeOpenCV() {
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "❌ OpenCV initialization failed!");
        } else {
            Log.d(TAG, "✅ OpenCV initialized successfully");
        }
    }

    private void initializeViews() {
        guidanceOverlay = findViewById(R.id.guidanceOverlay);
        instructionLabel = findViewById(R.id.instructionLabel);
        previewView = findViewById(R.id.viewFinder);
        documentTypeLabel = findViewById(R.id.documentTypeLabel);
        resultLabel = findViewById(R.id.resultLabel);
    }

    private void initializeManagers() {
        cameraExecutor = Executors.newSingleThreadExecutor();
        mrzParserManager = new MrzParserManager();

        Configuration config = DocumentReaderSDK.getInstance().getConfiguration();

        // Step 1: Create alignment detector (no dependencies)
        alignmentDetector = new DocumentAlignmentDetector(
                guidanceOverlay,
                previewView,
                config
        );

        // Step 2: Create camera manager WITHOUT detection handler
        cameraManager = new CameraManager(
                this,
                previewView,
                cameraExecutor
        );

        // Step 3: Create detection handler (needs cameraManager)
        detectionHandler = new MRZDetectionHandler(
                this,
                guidanceOverlay,
                instructionLabel,
                documentTypeLabel,
                resultLabel,
                mrzParserManager,
                alignmentDetector,
                cameraManager,
                config.processInterval
        );

        // Step 4: Now inject the detection handler into camera manager
        cameraManager.setDetectionHandler(detectionHandler);
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraManager.startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cameraManager.startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraManager != null) {
            cameraManager.cleanup();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (detectionHandler != null) {
            detectionHandler.cleanup();
        }
    }
}