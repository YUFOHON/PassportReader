package com.example.reader.camera;

import android.content.Context;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.example.reader.detection.MRZDetectionHandler;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class CameraManager {
    private static final String TAG = "CameraManager";

    private final LifecycleOwner lifecycleOwner;
    private final PreviewView previewView;
    private final ExecutorService executor;
    private MRZDetectionHandler detectionHandler; // Remove final
    private ImageCapture imageCapture;

    // Constructor without detectionHandler
    public CameraManager(LifecycleOwner lifecycleOwner, PreviewView previewView,
                         ExecutorService executor) {
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        this.executor = executor;
        this.detectionHandler = null; // Will be set later
    }

    // Setter for detection handler
    public void setDetectionHandler(MRZDetectionHandler detectionHandler) {
        this.detectionHandler = detectionHandler;
        Log.d(TAG, "✅ Detection handler injected");
    }

    public void startCamera() {
        Log.d(TAG, "🎥 Starting camera...");
        final var cameraProviderFuture = ProcessCameraProvider.getInstance(
                (Context) lifecycleOwner);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "❌ Camera binding failed", e);
            }
        }, ContextCompat.getMainExecutor((Context) lifecycleOwner));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        if (detectionHandler == null) {
            Log.e(TAG, "❌ Cannot bind camera: detectionHandler is null!");
            return;
        }
        int rotation = Surface.ROTATION_0;
        if (previewView.getDisplay() != null) {
            rotation = previewView.getDisplay().getRotation();
        }

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .setImageQueueDepth(2)
                .build();

        imageAnalysis.setAnalyzer(executor, detectionHandler::analyzeImage);

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build();

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis, imageCapture);

        Log.d(TAG, "✅ Camera started successfully");
    }

    public ImageCapture getImageCapture() {
        return imageCapture;
    }

    public void cleanup() {
        // Cleanup resources if needed
    }
}