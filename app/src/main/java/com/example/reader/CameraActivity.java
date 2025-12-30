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

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final long PROCESS_INTERVAL = 500; // Process every 500ms

    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private TextRecognizer recognizer;
    private long lastProcessTime = 0;
    private boolean isProcessing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView = findViewById(R.id.viewFinder);
        cameraExecutor = Executors.newSingleThreadExecutor();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        // Request Camera Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
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

                // Preview
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Image Analysis (The OCR part)
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720)) // Higher resolution for better OCR
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
        // Throttle processing to avoid overwhelming the system
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
                    .addOnSuccessListener(visionText -> {
                        processText(visionText);
                    })
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
            // Remove spaces and convert to uppercase
            line = line.replace(" ", "").toUpperCase();

            // Check for lines between 43-45 chars (allow some OCR variance)
            if (line.length() >= 43 && line.length() <= 45) {
                // Check if it matches MRZ pattern
                if (looksLikeMrzLine2(line)) {
                    try {
                        parseMrzLine2(line);
                        return; // Exit after successful parse
                    } catch (Exception e) {
                        Log.d(TAG, "Failed to parse potential MRZ line: " + line, e);
                    }
                }
            }
        }
    }

    private boolean looksLikeMrzLine2(String line) {
        // Line 2 typically starts with alphanumeric (doc number)
        // and contains date patterns (6 consecutive digits)

        // Check for date patterns (YYMMDD appears twice)
        Pattern datePattern = Pattern.compile("\\d{6}");
        Matcher matcher = datePattern.matcher(line);

        int dateCount = 0;
        while (matcher.find()) {
            dateCount++;
        }

        // Should have at least 2 date patterns (DOB and Expiry)
        return dateCount >= 2;
    }

    private String cleanMrzCharacters(String text) {
        return text
                .replace("O", "0")  // Letter O to zero
                .replace("Q", "0")
                .replace("D", "0")
                .replace("I", "1")  // Letter I to one
                .replace("l", "1")  // Lowercase L to one
                .replace("Z", "2")
                .replace("S", "5")
                .replace("B", "8")
                .replace("<", "")   // Remove filler characters
                .toUpperCase();
    }

    private boolean isValidDate(String date) {
        if (date.length() != 6) return false;

        try {
            int year = Integer.parseInt(date.substring(0, 2));
            int month = Integer.parseInt(date.substring(2, 4));
            int day = Integer.parseInt(date.substring(4, 6));

            // Basic date validation
            if (month < 1 || month > 12) return false;
            if (day < 1 || day > 31) return false;

            // More strict validation for months with fewer days
            if ((month == 4 || month == 6 || month == 9 || month == 11) && day > 30) {
                return false;
            }
            if (month == 2 && day > 29) {
                return false;
            }

            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean validateCheckDigit(String data, char checkDigit) {
        int[] weights = {7, 3, 1};
        int sum = 0;

        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int value;

            if (c == '<') {
                value = 0;
            } else if (Character.isDigit(c)) {
                value = c - '0';
            } else if (Character.isLetter(c)) {
                value = c - 'A' + 10;
            } else {
                return false; // Invalid character
            }

            sum += value * weights[i % 3];
        }

        int calculatedCheck = sum % 10;

        // Handle check digit
        int providedCheck;
        if (checkDigit == '<') {
            providedCheck = 0;
        } else if (Character.isDigit(checkDigit)) {
            providedCheck = checkDigit - '0';
        } else {
            return false;
        }

        return calculatedCheck == providedCheck;
    }

    private void parseMrzLine2(String line) {
        // Ensure line is exactly 44 characters
        if (line.length() < 44) {
            Log.d(TAG, "Line too short: " + line.length());
            return;
        }

        // Truncate if slightly longer (OCR might add extra char)
        line = line.substring(0, 44);

        // TD3 Format (Standard Passport) Line 2 Structure:
        // Chars 0-9:   Document Number
        // Char 9:      Check digit for document number
        // Chars 10-12: Nationality (3 letters)
        // Chars 13-19: Date of Birth (YYMMDD)
        // Char 19:     Check digit for DOB
        // Char 20:     Sex (M/F/<)
        // Chars 21-27: Expiry Date (YYMMDD)
        // Char 27:     Check digit for expiry

        try {
            String docNum = line.substring(0, 9);
            char docNumCheck = line.charAt(9);
            String nationality = line.substring(10, 13);
            String dob = line.substring(13, 19);
            char dobCheck = line.charAt(19);
            char sex = line.charAt(20);
            String expiry = line.substring(21, 27);
            char expiryCheck = line.charAt(27);

            Log.d(TAG, "Parsing MRZ - DocNum: " + docNum + ", DOB: " + dob + ", Expiry: " + expiry);

            // Validate check digits
            if (!validateCheckDigit(docNum, docNumCheck)) {
                Log.d(TAG, "Invalid document number check digit");
                return;
            }

            if (!validateCheckDigit(dob, dobCheck)) {
                Log.d(TAG, "Invalid DOB check digit");
                return;
            }

            if (!validateCheckDigit(expiry, expiryCheck)) {
                Log.d(TAG, "Invalid expiry check digit");
                return;
            }

            // Clean the extracted data
            docNum = cleanMrzCharacters(docNum);
            dob = cleanMrzCharacters(dob);
            expiry = cleanMrzCharacters(expiry);

            // Validate dates
            if (!isValidDate(dob)) {
                Log.d(TAG, "Invalid DOB date: " + dob);
                return;
            }

            if (!isValidDate(expiry)) {
                Log.d(TAG, "Invalid expiry date: " + expiry);
                return;
            }

            // Success! Return data to MainActivity
            Log.d(TAG, "Successfully parsed MRZ!");
            String finalDocNum = docNum;
            String finalDob = dob;
            String finalExpiry = expiry;
            runOnUiThread(() -> {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("DOC_NUM", finalDocNum);
                resultIntent.putExtra("DOB", finalDob);
                resultIntent.putExtra("EXPIRY", finalExpiry);
                resultIntent.putExtra("NATIONALITY", nationality);
                resultIntent.putExtra("SEX", String.valueOf(sex));
                setResult(RESULT_OK, resultIntent);
                finish();
            });

        } catch (Exception e) {
            Log.e(TAG, "Error parsing MRZ line", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        recognizer.close();
    }
}