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
import com.example.reader.utils.Constants;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

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
            case EEP:
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
            String docNum = data.getStringExtra(Constants.EXTRA_DOC_NUM);
            if (docNum == null) docNum = data.getStringExtra("DOC_NUM");

            String dob = data.getStringExtra(Constants.EXTRA_DOB);
            if (dob == null) dob = data.getStringExtra("DOB");

            String expiry = data.getStringExtra(Constants.EXTRA_EXPIRY);
            if (expiry == null) expiry = data.getStringExtra("EXPIRY");

            mrzLineCount = data.getIntExtra(Constants.EXTRA_MRZ_LINES, 0);
            String docTypeCode = data.getStringExtra(Constants.EXTRA_DOC_TYPE);

            // Determine document type based on MRZ lines or doc type code
//            detectedDocumentType = determineDocumentType(mrzLineCount, docTypeCode);
            detectedDocumentType = DocumentData.DocumentType.valueOf(data.getStringExtra(Constants.EXTRA_DOC_TYPE));

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
                case Constants.DOC_TYPE_TD3:
                    Log.d(TAG, "Detected TD3/PASSPORT from doc type code");
                    return DocumentData.DocumentType.PASSPORT;

                case Constants.DOC_TYPE_EEP_CHINA:
                    Log.d(TAG, "Detected EEP from doc type code");
                    return DocumentData.DocumentType.EEP;

                case Constants.DOC_TYPE_TD1:
                    Log.d(TAG, "Detected TD1/ID CARD from doc type code");
                    return DocumentData.DocumentType.PASSPORT;

                case Constants.DOC_TYPE_TD2:
                    Log.d(TAG, "Detected TD2 from doc type code");
                    return DocumentData.DocumentType.PASSPORT;

                case Constants.DOC_TYPE_MRVA:
                case Constants.DOC_TYPE_MRVB:
                    Log.d(TAG, "Detected VISA from doc type code");
                    return DocumentData.DocumentType.PASSPORT;

                default:
                    Log.d(TAG, "Unknown doc type code: " + docTypeCode);
                    break;
            }
        }

        // Fallback: use MRZ line count
        Log.d(TAG, "Determining document type from MRZ line count: " + mrzLines);
        switch (mrzLines) {
            case 1:
                return DocumentData.DocumentType.EEP;
            case 2:
                return DocumentData.DocumentType.PASSPORT;
            case 3:
                return DocumentData.DocumentType.ID_CARD;
            default:
                Log.w(TAG, "Unknown MRZ line count: " + mrzLines + ", defaulting to PASSPORT");
                return DocumentData.DocumentType.PASSPORT;
        }
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
                case EEP:
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

        // ========================================
        // SOD Section
        // ========================================
        result.append("───────────────────────────────\n");
        result.append("🔐 SECURITY OBJECT (SOD)\n");
        result.append("───────────────────────────────\n");

        if (data.rawSODData != null) {
            result.append("Status: ").append(data.hasValidSignature ? "Valid ✓" : "Present").append("\n");
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

            if (data.documentSignerCertificate != null) {
                result.append("\nCertificate Info:\n");
                String certInfo = data.documentSignerCertificate;
                if (certInfo.length() > 100) {
                    certInfo = certInfo.substring(0, 100) + "...";
                }
                result.append(certInfo).append("\n");
            }
        } else {
            result.append("⚠️ SOD not available\n");
        }
        result.append("\n");

        // ========================================
        // DG1 - Basic Information (MRZ)
        // ========================================
        result.append("───────────────────────────────\n");
        result.append("📄 BASIC INFORMATION (DG1 - MRZ)\n");
        result.append("───────────────────────────────\n");
        appendIfNotNull(result, "Document Type: ", data.documentCode);
        appendIfNotNull(result, "Document Number: ", data.documentNumber);
        appendIfNotNull(result, "First Name: ", data.firstName);
        appendIfNotNull(result, "Last Name: ", data.lastName);
        appendIfNotNull(result, "Nationality: ", data.nationality);
        appendIfNotNull(result, "Issuing Country: ", data.issuingCountry);
        appendIfNotNull(result, "Gender: ", data.gender);
        if (data.dateOfBirth != null) {
            result.append("Date of Birth: ").append(formatDate(data.dateOfBirth)).append("\n");
        }
        if (data.dateOfExpiry != null) {
            result.append("Date of Expiry: ").append(formatDate(data.dateOfExpiry)).append("\n");
        }
        appendIfNotNull(result, "Optional Data 1: ", data.optionalData1);
        appendIfNotNull(result, "Optional Data 2: ", data.optionalData2);
        result.append("\n");

        // ========================================
        // DG2 - Facial Image
        // ========================================
        result.append("───────────────────────────────\n");
        result.append("📸 FACIAL IMAGE (DG2)\n");
        result.append("───────────────────────────────\n");
        if (data.faceImages != null && !data.faceImages.isEmpty()) {
            result.append("Images Found: ").append(data.faceImages.size()).append(" photo(s)\n");
            for (int i = 0; i < data.faceImageMimeTypes.size(); i++) {
                result.append("  Image ").append(i + 1).append(": ").append(data.faceImageMimeTypes.get(i)).append("\n");
            }
            // Display first image
            imageFace.setImageBitmap(data.faceImages.get(0));

            Bitmap firstImage = data.faceImages.get(0);
            result.append("  Size: ").append(firstImage.getWidth()).append("x").append(firstImage.getHeight()).append(" pixels\n");
        } else {
            result.append("⚠️ No face image available\n");
        }
        result.append("\n");

        // ========================================
        // DG3 - Fingerprints
        // ========================================
        if (data.availableDataGroups.contains(3)) {
            result.append("───────────────────────────────\n");
            result.append("👆 FINGERPRINTS (DG3)\n");
            result.append("───────────────────────────────\n");

            if (data.hasFingerprintData && data.fingerprints != null && !data.fingerprints.isEmpty()) {
                result.append("Fingerprints Found: ").append(data.fingerprints.size()).append("\n");
                for (int i = 0; i < data.fingerprints.size(); i++) {
                    PassportData.FingerprintData fp = data.fingerprints.get(i);
                    result.append("  Fingerprint ").append(i + 1).append(":\n");
                    result.append("    Position: ").append(fp.fingerPosition).append("\n");
                    result.append("    Format: ").append(fp.imageFormat).append("\n");
                    result.append("    Size: ").append(fp.width).append("x").append(fp.height).append(" pixels\n");
                }
            } else {
                result.append("⚠️ No fingerprint data (EAC protected or not available)\n");
            }
            result.append("\n");
        }

        // ========================================
        // DG4 - Iris Scans
        // ========================================
        if (data.availableDataGroups.contains(4)) {
            result.append("───────────────────────────────\n");
            result.append("👁️ IRIS SCANS (DG4)\n");
            result.append("───────────────────────────────\n");

            if (data.hasIrisData && data.irisScans != null && !data.irisScans.isEmpty()) {
                result.append("Iris Scans Found: ").append(data.irisScans.size()).append("\n");
                for (int i = 0; i < data.irisScans.size(); i++) {
                    PassportData.IrisData iris = data.irisScans.get(i);
                    result.append("  Scan ").append(i + 1).append(":\n");
                    result.append("    Eye: ").append(iris.eyeLabel).append("\n");
                    result.append("    Format: ").append(iris.imageFormat).append("\n");
                }
            } else {
                result.append("⚠️ No iris data (EAC protected or not available)\n");
            }
            result.append("\n");
        }

        // ========================================
        // DG5 - Displayed Portrait
        // ========================================
        if (data.availableDataGroups.contains(5)) {
            result.append("───────────────────────────────\n");
            result.append("🖼️ DISPLAYED PORTRAIT (DG5)\n");
            result.append("───────────────────────────────\n");

            if (data.displayedPortrait != null) {
                result.append("Portrait Image: Present ✓\n");
                result.append("Size: ").append(data.displayedPortrait.getWidth()).append("x")
                        .append(data.displayedPortrait.getHeight()).append(" pixels\n");
            } else {
                result.append("⚠️ No portrait image\n");
            }
            result.append("\n");
        }

        // ========================================
        // DG6 - Reserved
        // ========================================
        if (data.availableDataGroups.contains(6)) {
            result.append("───────────────────────────────\n");
            result.append("📦 RESERVED DATA (DG6)\n");
            result.append("───────────────────────────────\n");

            if (data.dg6Data != null && data.dg6Data.length > 0) {
                result.append("Data Size: ").append(data.dg6Data.length).append(" bytes\n");
                result.append("(Country-specific data)\n");
            } else {
                result.append("⚠️ No DG6 data\n");
            }
            result.append("\n");
        }

        // ========================================
        // DG7 - Signature Image
        // ========================================
        if (data.availableDataGroups.contains(7)) {
            result.append("───────────────────────────────\n");
            result.append("✍️ SIGNATURE (DG7)\n");
            result.append("───────────────────────────────\n");

            if (data.signatureImage != null) {
                result.append("Signature Image: Present ✓\n");
                result.append("Size: ").append(data.signatureImage.getWidth()).append("x")
                        .append(data.signatureImage.getHeight()).append(" pixels\n");
            } else if (data.signatureImageData != null) {
                result.append("Signature Data: ").append(data.signatureImageData.length).append(" bytes\n");
            } else {
                result.append("⚠️ No signature image\n");
            }
            result.append("\n");
        }

        // ========================================
        // DG8 - Data Features (Security)
        // ========================================
        if (data.availableDataGroups.contains(8)) {
            result.append("───────────────────────────────\n");
            result.append("🔍 DATA FEATURES (DG8)\n");
            result.append("───────────────────────────────\n");

            if (data.dataFeatures != null && !data.dataFeatures.isEmpty()) {
                for (PassportData.DataFeature feature : data.dataFeatures) {
                    result.append("Type: ").append(feature.featureType).append("\n");
                    result.append("Description: ").append(feature.description).append("\n");
                    result.append("Data Size: ").append(feature.featureData != null ? feature.featureData.length : 0).append(" bytes\n");
                }
            } else {
                result.append("⚠️ No data features\n");
            }
            result.append("\n");
        }

        // ========================================
        // DG9 - Structure Features
        // ========================================
        if (data.availableDataGroups.contains(9)) {
            result.append("───────────────────────────────\n");
            result.append("🏗️ STRUCTURE FEATURES (DG9)\n");
            result.append("───────────────────────────────\n");

            if (data.structureFeatures != null && !data.structureFeatures.isEmpty()) {
                for (PassportData.StructureFeature feature : data.structureFeatures) {
                    result.append("Type: ").append(feature.featureType).append("\n");
                    result.append("Description: ").append(feature.description).append("\n");
                    result.append("Data Size: ").append(feature.featureData != null ? feature.featureData.length : 0).append(" bytes\n");
                }
            } else {
                result.append("⚠️ No structure features\n");
            }
            result.append("\n");
        }

        // ========================================
        // DG10 - Substance Features
        // ========================================
        if (data.availableDataGroups.contains(10)) {
            result.append("───────────────────────────────\n");
            result.append("⚗️ SUBSTANCE FEATURES (DG10)\n");
            result.append("───────────────────────────────\n");

            if (data.substanceFeatures != null && !data.substanceFeatures.isEmpty()) {
                for (PassportData.SubstanceFeature feature : data.substanceFeatures) {
                    result.append("Type: ").append(feature.substanceType).append("\n");
                    result.append("Description: ").append(feature.description).append("\n");
                    result.append("Data Size: ").append(feature.substanceData != null ? feature.substanceData.length : 0).append(" bytes\n");
                }
            } else {
                result.append("⚠️ No substance features\n");
            }
            result.append("\n");
        }

        // ========================================
        // DG11 - Additional Personal Details
        // ========================================
        if (data.availableDataGroups.contains(11)) {
            result.append("───────────────────────────────\n");
            result.append("ℹ️ ADDITIONAL PERSONAL DETAILS (DG11)\n");
            result.append("───────────────────────────────\n");

            appendIfNotNull(result, "Full Name: ", data.fullName);

            if (data.otherNames != null && !data.otherNames.isEmpty()) {
                result.append("Other Names:\n");
                for (String name : data.otherNames) {
                    result.append("  • ").append(name).append("\n");
                }
            }

            appendIfNotNull(result, "Personal Number: ", data.personalNumber);
            appendIfNotNull(result, "Place of Birth: ", data.placeOfBirth);
            appendIfNotNull(result, "Full Date of Birth: ", data.dateOfBirth_Full);

            if (data.address != null && !data.address.isEmpty()) {
                result.append("Address:\n");
                for (String addressLine : data.address) {
                    result.append("  ").append(addressLine).append("\n");
                }
            }

            appendIfNotNull(result, "Telephone: ", data.telephone);
            appendIfNotNull(result, "Profession: ", data.profession);
            appendIfNotNull(result, "Title: ", data.title);
            appendIfNotNull(result, "Personal Summary: ", data.personalSummary);

            if (data.proofOfCitizenship != null && data.proofOfCitizenship.length > 0) {
                result.append("Proof of Citizenship: ").append(data.proofOfCitizenship.length).append(" bytes\n");
            }

            if (data.otherValidTravelDocNumbers != null && !data.otherValidTravelDocNumbers.isEmpty()) {
                result.append("Other Travel Documents:\n");
                for (String doc : data.otherValidTravelDocNumbers) {
                    result.append("  • ").append(doc).append("\n");
                }
            }

            appendIfNotNull(result, "Custody Info: ", data.custodyInformation);
            result.append("\n");
        }

        // ========================================
        // DG12 - Additional Document Details
        // ========================================
        if (data.availableDataGroups.contains(12)) {
            result.append("───────────────────────────────\n");
            result.append("📋 ADDITIONAL DOCUMENT DETAILS (DG12)\n");
            result.append("───────────────────────────────\n");

            appendIfNotNull(result, "Issuing Authority: ", data.issuingAuthority);
            appendIfNotNull(result, "Date of Issue: ", data.dateOfIssue);

            if (data.namesOfOtherPersons != null && !data.namesOfOtherPersons.isEmpty()) {
                result.append("Names of Other Persons:\n");
                for (String person : data.namesOfOtherPersons) {
                    result.append("  • ").append(person).append("\n");
                }
            }

            appendIfNotNull(result, "Endorsements & Observations: ", data.endorsementsAndObservations);
            appendIfNotNull(result, "Tax/Exit Requirements: ", data.taxOrExitRequirements);

            if (data.imageOfFront != null && data.imageOfFront.length > 0) {
                result.append("Front Image: ").append(data.imageOfFront.length).append(" bytes\n");
            }

            if (data.imageOfRear != null && data.imageOfRear.length > 0) {
                result.append("Rear Image: ").append(data.imageOfRear.length).append(" bytes\n");
            }

            appendIfNotNull(result, "Personalization Date/Time: ", data.dateAndTimeOfPersonalization);
            appendIfNotNull(result, "Personalization System S/N: ", data.personalizationSystemSerialNumber);
            result.append("\n");
        }

        // ========================================
        // DG13 - Optional Details
        // ========================================
        if (data.availableDataGroups.contains(13)) {
            result.append("───────────────────────────────\n");
            result.append("📦 OPTIONAL DETAILS (DG13)\n");
            result.append("───────────────────────────────\n");

            if (data.optionalDetailsData != null && data.optionalDetailsData.length > 0) {
                result.append("Data Size: ").append(data.optionalDetailsData.length).append(" bytes\n");
            } else {
                result.append("⚠️ No optional details\n");
            }
            result.append("\n");
        }

        // ========================================
        // DG14 - Security Options
        // ========================================
        if (data.availableDataGroups.contains(14)) {
            result.append("───────────────────────────────\n");
            result.append("🔐 SECURITY OPTIONS (DG14)\n");
            result.append("───────────────────────────────\n");

            result.append("Chip Authentication: ").append(data.hasChipAuthentication ? "Supported ✓" : "Not supported").append("\n");
            if (data.chipAuthAlgorithm != null) {
                result.append("  Algorithm: ").append(data.chipAuthAlgorithm).append("\n");
            }

            result.append("Terminal Authentication: ").append(data.hasTerminalAuthentication ? "Supported ✓" : "Not supported").append("\n");

            if (data.supportedSecurityProtocols != null && !data.supportedSecurityProtocols.isEmpty()) {
                result.append("Security Protocols:\n");
                for (String protocol : data.supportedSecurityProtocols) {
                    result.append("  • ").append(protocol).append("\n");
                }
            }
            result.append("\n");
        }

        // ========================================
        // DG15 - Active Authentication
        // ========================================
        if (data.availableDataGroups.contains(15)) {
            result.append("───────────────────────────────\n");
            result.append("🔑 ACTIVE AUTHENTICATION (DG15)\n");
            result.append("───────────────────────────────\n");

            result.append("Active Authentication: ").append(data.hasActiveAuthentication ? "Supported ✓" : "Not supported").append("\n");

            if (data.activeAuthPublicKey != null) {
                result.append("Public Key Algorithm: ").append(data.activeAuthAlgorithm != null ? data.activeAuthAlgorithm : "Unknown").append("\n");
                result.append("Public Key Format: ").append(data.activeAuthPublicKey.getFormat()).append("\n");
            }

            result.append("AA Performed: ").append(data.activeAuthenticationPerformed ? "Yes ✓" : "No").append("\n");
            result.append("\n");
        }

        // ========================================
        // DG16 - Emergency Contacts
        // ========================================
        if (data.availableDataGroups.contains(16)) {
            result.append("───────────────────────────────\n");
            result.append("🆘 EMERGENCY CONTACTS (DG16)\n");
            result.append("───────────────────────────────\n");

            if (data.emergencyContacts != null && !data.emergencyContacts.isEmpty()) {
                for (int i = 0; i < data.emergencyContacts.size(); i++) {
                    PassportData.EmergencyContact contact = data.emergencyContacts.get(i);
                    result.append("Contact ").append(i + 1).append(":\n");
                    appendIfNotNull(result, "  Name: ", contact.name);
                    appendIfNotNull(result, "  Telephone: ", contact.telephone);
                    appendIfNotNull(result, "  Address: ", contact.address);
                    appendIfNotNull(result, "  Message: ", contact.message);
                }
            } else {
                result.append("⚠️ No emergency contacts\n");
            }
            result.append("\n");
        }

        // ========================================
        // Security Summary
        // ========================================
        result.append("───────────────────────────────\n");
        result.append("🔐 SECURITY SUMMARY\n");
        result.append("───────────────────────────────\n");
        appendIfNotNull(result, "Authentication Method: ", data.authenticationMethod);
        result.append("Chip Authentication: ").append(data.hasChipAuthentication ? "Yes ✓" : "No").append("\n");
        result.append("  Performed: ").append(data.chipAuthenticationPerformed ? "Yes ✓" : "No").append("\n");
        result.append("Active Authentication: ").append(data.hasActiveAuthentication ? "Yes ✓" : "No").append("\n");
        result.append("  Performed: ").append(data.activeAuthenticationPerformed ? "Yes ✓" : "No").append("\n");
        result.append("Digital Signature: ").append(data.hasValidSignature ? "Valid ✓" : "Not verified").append("\n");
        result.append("\n");

        // ========================================
        // Available Data Groups
        // ========================================
        result.append("───────────────────────────────\n");
        result.append("📊 DATA GROUPS SUMMARY\n");
        result.append("───────────────────────────────\n");
        result.append("Available DGs: ");
        if (data.availableDataGroups != null && !data.availableDataGroups.isEmpty()) {
            TreeSet<Integer> sortedDGs = new TreeSet<>(data.availableDataGroups);
            for (Integer dg : sortedDGs) {
                result.append("DG").append(dg).append(" ");
            }
        } else {
            result.append("None");
        }
        result.append("\n\n");

        // ========================================
        // Metadata
        // ========================================
        appendIfNotNull(result, "Passport Type: ", data.passportType);

        result.append("═══════════════════════════════\n");
        result.append("✅ Read completed successfully\n");
        result.append("Total Data Groups: ").append(data.availableDataGroups != null ? data.availableDataGroups.size() : 0).append("\n");
        result.append("═══════════════════════════════");

        tvResult.setText(result.toString());
    }

    // Helper method to append non-null values
    private void appendIfNotNull(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(label).append(value).append("\n");
        }
    }

    // Helper method to format dates from YYMMDD to readable format
    private String formatDate(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6) {
            return yymmdd;
        }
        try {
            String yy = yymmdd.substring(0, 2);
            String mm = yymmdd.substring(2, 4);
            String dd = yymmdd.substring(4, 6);

            // Convert YY to YYYY (assuming 00-30 = 2000-2030, 31-99 = 1931-1999)
            int year = Integer.parseInt(yy);
            String yyyy = (year <= 30) ? "20" + yy : "19" + yy;

            return dd + "/" + mm + "/" + yyyy;
        } catch (Exception e) {
            return yymmdd;
        }
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
        result.append("Date of Birth: ").append(data.dateOfBirth).append("\n");
        result.append("Date of Expiry: ").append(data.dateOfExpiry).append("\n");
        result.append("Place of Birth: ").append(data.placeOfBirth).append("\n");

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