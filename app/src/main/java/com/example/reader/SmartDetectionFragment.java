package com.example.reader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.reader.models.DocumentData;
import com.example.reader.models.EepData;
import com.example.reader.models.PassportData;
import com.example.reader.readers.DocumentAuthData;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class SmartDetectionFragment extends Fragment {

    private static final int REQUEST_CODE_CAMERA = 102;
    private static final String TAG = "@@>> SmartDetectionFragment";
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    // Views
    private TextInputEditText edDocNum, edBirthDate, edExpiryDate;
    private TextView tvStatus, tvResult, tvDetectedType;
    private ImageView imageFace;
    private Button btnScan, btnReadNfc;

    // Document reading
    private UniversalDocumentReader documentReader;
    private boolean waitingForNfc = false;

    // NFC Components
    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private boolean nfcEnabled = false;

    // Detected document type
    private DocumentData.DocumentType detectedDocumentType = null;
    private int mrzLineCount = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_smart_detection, container, false);

        // Initialize Views
        edDocNum = view.findViewById(R.id.ed_doc_number);
        edBirthDate = view.findViewById(R.id.ed_birth_date);
        edExpiryDate = view.findViewById(R.id.ed_expiry_date);
        tvStatus = view.findViewById(R.id.tv_status);
        tvResult = view.findViewById(R.id.tv_result);
        tvDetectedType = view.findViewById(R.id.tv_detected_type);
        imageFace = view.findViewById(R.id.image_face);
        btnScan = view.findViewById(R.id.btn_scan);
        btnReadNfc = view.findViewById(R.id.btn_read_nfc);

        // Initialize NFC
        initializeNfc();

        // Initialize Universal Document Reader
        documentReader = new UniversalDocumentReader(getContext());
        setupDocumentReaderCallback();

        // Setup Camera Button
        btnScan.setOnClickListener(v -> {
            // Reset detected type when scanning new document
            detectedDocumentType = null;
            mrzLineCount = 0;
            updateDetectedTypeDisplay();

            Intent intent = new Intent(getActivity(), CameraActivity.class);
            startActivityForResult(intent, REQUEST_CODE_CAMERA);
        });

        // Setup Read NFC Button
        btnReadNfc.setOnClickListener(v -> startNfcReading());

        // Initially disable NFC button until document is scanned
        btnReadNfc.setEnabled(false);

        return view;
    }

    private void initializeNfc() {
        if (getActivity() == null) return;

        nfcAdapter = NfcAdapter.getDefaultAdapter(getActivity());

        if (nfcAdapter == null) {
            Toast.makeText(getContext(), "NFC not supported on this device", Toast.LENGTH_LONG).show();
            if (btnReadNfc != null) {
                btnReadNfc.setEnabled(false);
            }
            return;
        }

        if (!nfcAdapter.isEnabled()) {
            Toast.makeText(getContext(), "Please enable NFC in settings", Toast.LENGTH_LONG).show();
        }

        Intent intent = new Intent(getActivity(), getActivity().getClass());
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(
                getActivity(),
                0,
                intent,
                PendingIntent.FLAG_MUTABLE
        );
    }

    private void enableNfcForegroundDispatch() {
        if (getActivity() == null || nfcAdapter == null || nfcEnabled) {
            return;
        }

        try {
            String[][] techList = new String[][]{{IsoDep.class.getName()}};
            nfcAdapter.enableForegroundDispatch(getActivity(), pendingIntent, null, techList);
            nfcEnabled = true;
            Log.d(TAG, "NFC foreground dispatch enabled");
        } catch (Exception e) {
            Log.e(TAG, "Error enabling NFC: " + e.getMessage());
            Toast.makeText(getContext(), "Error enabling NFC", Toast.LENGTH_SHORT).show();
        }
    }

    private void disableNfcForegroundDispatch() {
        if (getActivity() == null || nfcAdapter == null || !nfcEnabled) {
            return;
        }

        try {
            nfcAdapter.disableForegroundDispatch(getActivity());
            nfcEnabled = false;
            Log.d(TAG, "NFC foreground dispatch disabled");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling NFC: " + e.getMessage());
        }
    }

    private void setupDocumentReaderCallback() {
        documentReader.setCallback(new UniversalDocumentReader.DocumentReadCallback() {
            @Override
            public void onReadStart(DocumentData.DocumentType expectedType) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        String docTypeName = getDocumentTypeName(expectedType);
                        tvStatus.setText("📱 Reading " + docTypeName + "... DO NOT MOVE");
                        btnReadNfc.setEnabled(false);
                    });
                }
            }

            @Override
            public void onReadProgress(String message, int progress) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        tvStatus.setText("⏳ " + message + " (" + progress + "%)");
                    });
                }
            }

            @Override
            public void onReadSuccess(DocumentData data) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        displayDocumentData(data);
                        btnReadNfc.setEnabled(true);
                        waitingForNfc = false;
                        disableNfcForegroundDispatch();
                        Toast.makeText(getContext(), "✅ Read complete!", Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onReadError(String errorMessage, Exception exception) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        tvStatus.setText("❌ Error: " + errorMessage);
                        Toast.makeText(getContext(), "Read failed: " + errorMessage, Toast.LENGTH_LONG).show();
                        btnReadNfc.setEnabled(true);
                        waitingForNfc = false;
                        disableNfcForegroundDispatch();
                    });
                }
            }
        });
    }

    private String getDocumentTypeName(DocumentData.DocumentType type) {
        if (type == null) return "Document";
        switch (type) {
            case PASSPORT:
                return "Passport";
            case EEEP:
                return "HK/Macao Travel Permit";
            default:
                return "Document";
        }
    }

    private void startNfcReading() {
        String docNum = edDocNum.getText().toString().trim();
        String birthDate = edBirthDate.getText().toString().trim();
        String expiryDate = edExpiryDate.getText().toString().trim();

        if (docNum.isEmpty() || birthDate.isEmpty() || expiryDate.isEmpty()) {
            Toast.makeText(getContext(), "Please scan MRZ first to get document data", Toast.LENGTH_SHORT).show();
            return;
        }

        if (detectedDocumentType == null) {
            Toast.makeText(getContext(), "Document type not detected. Please scan MRZ again.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (nfcAdapter == null) {
            Toast.makeText(getContext(), "NFC is not supported on this device", Toast.LENGTH_LONG).show();
            return;
        }

        if (!nfcAdapter.isEnabled()) {
            Toast.makeText(getContext(), "Please enable NFC in settings", Toast.LENGTH_LONG).show();
            return;
        }

        waitingForNfc = true;
        String docTypeName = getDocumentTypeName(detectedDocumentType);
        tvStatus.setText("📱 NFC Activated! Tap " + docTypeName + " to back of phone...");
        tvResult.setText("");
        imageFace.setImageBitmap(null);

        enableNfcForegroundDispatch();
        Toast.makeText(getContext(), "NFC ready - Tap your " + docTypeName + " now", Toast.LENGTH_LONG).show();
    }

    public void handleNfcIntent(Intent intent) {
        if (!waitingForNfc) {
            Toast.makeText(getContext(), "Please click 'Read NFC' button first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (detectedDocumentType == null) {
            Toast.makeText(getContext(), "Document type not detected. Please scan MRZ first.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

            String docNum = edDocNum.getText().toString().trim();
            String birthDate = edBirthDate.getText().toString().trim();
            String expiryDate = edExpiryDate.getText().toString().trim();

            DocumentAuthData authData = new DocumentAuthData(docNum, birthDate, expiryDate);

            // Use the detected document type for reading
            Log.d(TAG, "Reading document with detected type: " + detectedDocumentType);
            documentReader.readDocument(tag, authData, detectedDocumentType);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_CAMERA && resultCode == Activity.RESULT_OK && data != null) {
            String docNum = data.getStringExtra(CameraActivity.EXTRA_DOC_NUM);
            if (docNum == null) docNum = data.getStringExtra("DOC_NUM");

            String dob = data.getStringExtra(CameraActivity.EXTRA_DOB);
            if (dob == null) dob = data.getStringExtra("DOB");

            String expiry = data.getStringExtra(CameraActivity.EXTRA_EXPIRY);
            if (expiry == null) expiry = data.getStringExtra("EXPIRY");

            mrzLineCount = data.getIntExtra(CameraActivity.EXTRA_MRZ_LINES, 0);
            String docTypeCode = data.getStringExtra(CameraActivity.EXTRA_DOC_TYPE);

            // Determine document type based on MRZ lines or doc type code
            detectedDocumentType = determineDocumentType(mrzLineCount, docTypeCode);

            final String finalDocNum = docNum;
            final String finalDob = dob;
            final String finalExpiry = expiry;

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (finalDocNum != null && !finalDocNum.isEmpty()) {
                        edDocNum.setText(finalDocNum);
                    }
                    if (finalDob != null && !finalDob.isEmpty()) {
                        edBirthDate.setText(finalDob);
                    }
                    if (finalExpiry != null && !finalExpiry.isEmpty()) {
                        edExpiryDate.setText(finalExpiry);
                    }

                    // Update detected type display
                    updateDetectedTypeDisplay();

                    if (detectedDocumentType != null) {
                        String docTypeName = getDocumentTypeName(detectedDocumentType);
                        tvStatus.setText("✅ " + docTypeName + " detected! Click 'Read NFC' button and tap document.");
                        btnReadNfc.setEnabled(true);
                        Toast.makeText(getContext(), "Detected: " + docTypeName, Toast.LENGTH_SHORT).show();
                    } else {
                        tvStatus.setText("⚠️ Could not detect document type. Please try scanning again.");
                        btnReadNfc.setEnabled(false);
                    }
                });
            }
        } else if (requestCode == REQUEST_CODE_CAMERA && resultCode == Activity.RESULT_CANCELED) {
            if (tvStatus != null) {
                tvStatus.setText("Scan cancelled. Try again.");
            }
        }
    }

    /**
     * Determine document type based on MRZ line count and doc type code
     */
    private DocumentData.DocumentType determineDocumentType(int mrzLines, String docTypeCode) {
        // First try using the explicit doc type code
        if (docTypeCode != null) {
            switch (docTypeCode) {
                case CameraActivity.DOC_TYPE_PASSPORT:
                    Log.d(TAG, "Detected PASSPORT from doc type code");
                    return DocumentData.DocumentType.PASSPORT;
                case CameraActivity.DOC_TYPE_EEP:
                    Log.d(TAG, "Detected EEP from doc type code");
                    return DocumentData.DocumentType.EEEP;
            }
        }

        // Fall back to MRZ line count
        // TD3 Passport: 2 MRZ lines (machine readable zone)
        // TD1 ID Card: 3 MRZ lines
        // EEP: 1 MRZ line
        if (mrzLines >= 2) {
            Log.d(TAG, "Detected PASSPORT from MRZ line count: " + mrzLines);
            return DocumentData.DocumentType.PASSPORT;
        } else if (mrzLines == 1) {
            Log.d(TAG, "Detected EEP from MRZ line count: " + mrzLines);
            return DocumentData.DocumentType.EEEP;
        }

        Log.w(TAG, "Could not determine document type, mrzLines=" + mrzLines + ", docTypeCode=" + docTypeCode);
        return null;
    }

    private void updateDetectedTypeDisplay() {
        if (tvDetectedType == null) return;

        if (detectedDocumentType == null) {
            tvDetectedType.setText("Detected Type: Not scanned");
            tvDetectedType.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        } else {
            String icon;
            String typeName;
            switch (detectedDocumentType) {
                case PASSPORT:
                    icon = "📘";
                    typeName = "Passport (TD3)";
                    break;
                case EEEP:
                    icon = "📄";
                    typeName = "HK/Macao Travel Permit";
                    break;
                default:
                    icon = "📋";
                    typeName = "Unknown";
            }
            tvDetectedType.setText(icon + " Detected: " + typeName + " (MRZ: " + mrzLineCount + " line" + (mrzLineCount != 1 ? "s" : "") + ")");
        }
    }

    /**
     * Display document data based on type
     */
    private void displayDocumentData(DocumentData data) {
        if (data instanceof PassportData) {
            displayPassportData((PassportData) data);
        } else if (data instanceof EepData) {
            displayEepData((EepData) data);
        } else {
            displayGenericDocumentData(data);
        }
    }

    private void displayGenericDocumentData(DocumentData data) {
        tvStatus.setText("✅ Document Read Complete!");
        StringBuilder result = new StringBuilder();
        result.append("═══════════════════════════════\n");
        result.append("📄 DOCUMENT INFORMATION\n");
        result.append("═══════════════════════════════\n\n");

        result.append("Document Type: ").append(data.documentType.name()).append("\n");
        result.append("Document Number: ").append(data.documentNumber).append("\n");
        result.append("Name: ").append(data.firstName).append(" ").append(data.lastName).append("\n");
        result.append("Nationality: ").append(data.nationality).append("\n");
        result.append("Date of Birth: ").append(formatDate(data.dateOfBirth)).append("\n");
        result.append("Date of Expiry: ").append(formatDate(data.dateOfExpiry)).append("\n");

        if (!data.faceImages.isEmpty()) {
            imageFace.setImageBitmap(data.faceImages.get(0));
        }

        tvResult.setText(result.toString());
    }

    private void displayPassportData(PassportData data) {
        tvStatus.setText("✅ Passport Read Complete!");
        StringBuilder result = new StringBuilder();
        result.append("═══════════════════════════════\n");
        result.append("📘 PASSPORT INFORMATION\n");
        result.append("═══════════════════════════════\n\n");

        // SOD Section
        result.append("───────────────────────────────\n");
        result.append("🔐 SECURITY OBJECT (SOD)\n");
        result.append("───────────────────────────────\n");

        if (data.rawSODData != null) {
            result.append("Status: ").append(data.hasValidSignature ? "Present ✓" : "Present").append("\n");
            result.append("Signer: ").append(data.signingCountry != null ? data.signingCountry : "Unknown").append("\n");
            result.append("Size: ").append(data.rawSODData.length).append(" bytes\n");

            if (data.dataGroupHashes != null && !data.dataGroupHashes.isEmpty()) {
                result.append("\nData Group Hashes:\n");
                TreeMap<Integer, byte[]> sortedHashes = new TreeMap<>(data.dataGroupHashes);
                for (Map.Entry<Integer, byte[]> entry : sortedHashes.entrySet()) {
                    String hashHex = bytesToHex(entry.getValue());
                    if (hashHex.length() > 16) {
                        hashHex = hashHex.substring(0, 16) + "...";
                    }
                    result.append("  DG").append(entry.getKey()).append(": ").append(hashHex).append("\n");
                }
            }
        } else {
            result.append("⚠️ SOD not available\n");
        }
        result.append("\n");

        // Basic Information
        result.append("───────────────────────────────\n");
        result.append("📄 BASIC INFORMATION (DG1)\n");
        result.append("───────────────────────────────\n");
        result.append("Document Type: ").append(data.documentCode).append("\n");
        result.append("Document Number: ").append(data.documentNumber).append("\n");
        result.append("Name: ").append(data.firstName).append(" ").append(data.lastName).append("\n");
        result.append("Nationality: ").append(data.nationality).append("\n");
        result.append("Issuing Country: ").append(data.issuingCountry).append("\n");
        result.append("Gender: ").append(data.gender).append("\n");
        result.append("Date of Birth: ").append(formatDate(data.dateOfBirth)).append("\n");
        result.append("Date of Expiry: ").append(formatDate(data.dateOfExpiry)).append("\n");
        result.append("\n");

        // Face Image
        result.append("📸 FACIAL IMAGE (DG2)\n");
        result.append("───────────────────────────────\n");
        if (!data.faceImages.isEmpty()) {
            result.append("Images: ").append(data.faceImages.size()).append(" photo(s)\n");
            imageFace.setImageBitmap(data.faceImages.get(0));
        } else {
            result.append("No face image available\n");
        }
        result.append("\n");

        // Security Information
        result.append("🔐 SECURITY INFORMATION\n");
        result.append("───────────────────────────────\n");
        result.append("Authentication: ").append(data.authenticationMethod).append("\n");
        result.append("Chip Auth: ").append(data.hasChipAuthentication ? "Yes ✓" : "No").append("\n");
        result.append("Active Auth: ").append(data.hasActiveAuthentication ? "Yes ✓" : "No").append("\n");
        result.append("Digital Signature: ").append(data.hasValidSignature ? "Valid ✓" : "Not verified").append("\n");
        result.append("\n");

        // Available Data Groups
        result.append("📊 DATA GROUPS PRESENT\n");
        result.append("───────────────────────────────\n");
        result.append("Available: ");
        for (Integer dg : data.availableDataGroups) {
            result.append("DG").append(dg).append(" ");
        }
        result.append("\n\n");

        result.append("═══════════════════════════════\n");
        result.append("✅ Read completed successfully\n");
        result.append("═══════════════════════════════");

        tvResult.setText(result.toString());
    }

    private void displayEepData(EepData data) {
        tvStatus.setText("✅ HK/Macao Travel Permit Read Complete!");
        StringBuilder result = new StringBuilder();

        result.append("═══════════════════════════════\n");
        result.append("📄 HK/MACAO TRAVEL PERMIT\n");
        result.append("═══════════════════════════════\n\n");

        // Basic Information
        result.append("───────────────────────────────\n");
        result.append("📋 BASIC INFORMATION\n");
        result.append("───────────────────────────────\n");

        if (data.chineseName != null) {
            result.append("Chinese Name: ").append(data.chineseName).append("\n");
        }
        if (data.pinyinName != null) {
            result.append("Pinyin Name: ").append(data.pinyinName).append("\n");
        }
        result.append("Card Number: ").append(data.cardNumber != null ? data.cardNumber : data.documentNumber).append("\n");
        result.append("Nationality: ").append(data.nationality != null ? data.nationality : "CHN").append("\n");
        result.append("Gender: ").append(data.gender != null ? data.gender : "Unknown").append("\n");
        result.append("Date of Birth: ").append(formatDate(data.dateOfBirth)).append("\n");
        result.append("Date of Expiry: ").append(formatDate(data.dateOfExpiry)).append("\n");
        result.append("\n");

        // Validity
        result.append("───────────────────────────────\n");
        result.append("✈️ VALIDITY\n");
        result.append("───────────────────────────────\n");
        if (data.validForHongKong) {
            result.append("  ✓ Hong Kong\n");
        }
        if (data.validForMacao) {
            result.append("  ✓ Macao\n");
        }
        result.append("\n");

        // Face Image
        if (!data.faceImages.isEmpty()) {
            result.append("📸 BIOMETRIC DATA\n");
            result.append("───────────────────────────────\n");
            result.append("Face Image: Available\n");
            imageFace.setImageBitmap(data.faceImages.get(0));
            result.append("\n");
        }

        // SOD Information
        if (data.sodPresent) {
            result.append("───────────────────────────────\n");
            result.append("🔏 SOD (Security Object)\n");
            result.append("───────────────────────────────\n");
            result.append("Digest Algorithm: ").append(data.sodDigestAlgorithm != null ? data.sodDigestAlgorithm : "N/A").append("\n");
            result.append("Size: ").append(data.sodRawSize).append(" bytes\n");

            if (data.dataGroupHashes != null && !data.dataGroupHashes.isEmpty()) {
                result.append("\nData Group Hashes:\n");
                List<Integer> sortedDgs = new ArrayList<>(data.dataGroupHashes.keySet());
                Collections.sort(sortedDgs);
                for (Integer dgNum : sortedDgs) {
                    String hash = data.dataGroupHashes.get(dgNum);
                    if (hash != null && hash.length() > 16) {
                        hash = hash.substring(0, 16) + "...";
                    }
                    result.append("  DG").append(dgNum).append(": ").append(hash).append("\n");
                }
            }
            result.append("\n");
        }

        result.append("═══════════════════════════════\n");
        result.append("✅ Read completed successfully\n");
        result.append("═══════════════════════════════");

        tvResult.setText(result.toString());
    }

    private String formatDate(String yymmdd) {
        if (yymmdd == null || yymmdd.isEmpty()) {
            return "N/A";
        }

        String digits = yymmdd.replaceAll("[^0-9]", "");

        if (digits.length() == 6) {
            try {
                String year = digits.substring(0, 2);
                String month = digits.substring(2, 4);
                String day = digits.substring(4, 6);

                int yy = Integer.parseInt(year);
                int fullYear = (yy > 50) ? (1900 + yy) : (2000 + yy);

                return day + "/" + month + "/" + fullYear;
            } catch (NumberFormatException e) {
                return yymmdd;
            }
        }

        if (digits.length() == 8) {
            try {
                String year = digits.substring(0, 4);
                String month = digits.substring(4, 6);
                String day = digits.substring(6, 8);
                return day + "/" + month + "/" + year;
            } catch (Exception e) {
                return yymmdd;
            }
        }

        return yymmdd;
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (nfcAdapter == null) {
            initializeNfc();
        }
        if (waitingForNfc) {
            enableNfcForegroundDispatch();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        disableNfcForegroundDispatch();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (documentReader != null) {
            documentReader.cancelRead();
        }
        disableNfcForegroundDispatch();
    }
}