package com.example.reader.camera;

import android.content.Context;
import android.util.Log;

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
    private MRZDetectionHandler detectionHandler;
    private ImageCapture imageCapture;

    public CameraManager(LifecycleOwner lifecycleOwner, PreviewView previewView,
                         ExecutorService executor) {
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        this.executor = executor;
        this.detectionHandler = null;
    }

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

        int rotation = android.view.Surface.ROTATION_0;
        if (previewView.getDisplay() != null) {
            rotation = previewView.getDisplay().getRotation();
        }

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Optimized ImageAnalysis for faster processing
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .setImageQueueDepth(1) // Reduced from 2 for faster processing
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();

        imageAnalysis.setAnalyzer(executor, detectionHandler::analyzeImage);

        // High quality capture for final image
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // Changed for speed
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build();

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis, imageCapture);

        Log.d(TAG, "✅ Camera started successfully with optimized settings");
    }

    public ImageCapture getImageCapture() {
        return imageCapture;
    }

    public void cleanup() {
        Log.d(TAG, "🧹 CameraManager cleanup");
    }
}