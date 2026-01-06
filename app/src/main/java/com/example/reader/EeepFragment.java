package com.example.reader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
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
import com.example.reader.readers.DocumentAuthData;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EeepFragment extends Fragment {

    private static final int REQUEST_CODE_CAMERA = 101;
    private static final String TAG = "@@>> EpppFragment";

    private TextInputEditText edCardNum, edBirthDate, edExpiryDate;
    private TextView tvStatus, tvResult;
    private ImageView imageFace;
    private Button btnScan, btnReadNfc;

    private UniversalDocumentReader documentReader;
    private boolean waitingForNfc = false;

    // NFC Components
    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private boolean nfcEnabled = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_eppp, container, false);

        // Initialize Views
        edCardNum = view.findViewById(R.id.ed_card_number);
        edBirthDate = view.findViewById(R.id.ed_birth_date);
        edExpiryDate = view.findViewById(R.id.ed_expiry_date);
        tvStatus = view.findViewById(R.id.tv_status);
        tvResult = view.findViewById(R.id.tv_result);
        imageFace = view.findViewById(R.id.image_face);
        btnScan = view.findViewById(R. id.btn_scan);
        btnReadNfc = view.findViewById(R.id.btn_read_nfc);

        // Initialize NFC
        initializeNfc();

        // Initialize Universal Document Reader
        documentReader = new UniversalDocumentReader(getContext());
        setupDocumentReaderCallback();

        // Setup Camera Button
        btnScan.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), CameraActivity.class);
            startActivityForResult(intent, REQUEST_CODE_CAMERA);
        });

        // Setup Read NFC Button
        btnReadNfc.setOnClickListener(v -> startNfcReading());

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
                        tvStatus.setText("📱 Reading HK/Macao Travel Permit... DO NOT MOVE");
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
                        displayEepData((EepData) data);
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

    private void startNfcReading() {
        String cardNum = edCardNum.getText().toString().trim();
        String birthDate = edBirthDate.getText().toString().trim();
        String expiryDate = edExpiryDate.getText().toString().trim();

        if (cardNum.isEmpty() || birthDate.isEmpty() || expiryDate.isEmpty()) {
            Toast.makeText(getContext(), "Please enter card number, birth date, and expiry date", Toast.LENGTH_SHORT).show();
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
        tvStatus.setText("📱 NFC Activated! Now tap HK/Macao Travel Permit to back of phone...");
        tvResult.setText("");
        imageFace.setImageBitmap(null);

        enableNfcForegroundDispatch();
        Toast.makeText(getContext(), "NFC ready - Tap your permit now", Toast.LENGTH_LONG).show();
    }

    public void handleNfcIntent(Intent intent) {
        if (!waitingForNfc) {
            Toast.makeText(getContext(), "Please click 'Read NFC' button first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

            String cardNum = edCardNum.getText().toString().trim();
            String birthDate = edBirthDate.getText().toString().trim();
            String expiryDate = edExpiryDate.getText().toString().trim();

            DocumentAuthData authData = new DocumentAuthData(cardNum, birthDate, expiryDate);
            documentReader.readDocument(tag, authData, DocumentData.DocumentType.EEEP);
        }
    }

    private void displayEepData(EepData data) {
        tvStatus.setText("✅ HK Travel Permit Read Complete!");
        StringBuilder result = new StringBuilder();

        result.append("═══════════════════════════════\n");
        result.append("🇨🇳 HONG KONG & MACAO TRAVEL PERMIT\n");
        result.append("═══════════════════════════════\n\n");

        // ... (keep all your existing code for BASIC INFORMATION, ADDRESS, etc.)

        result.append("───────────────────────────────\n");
        result.append("📄 BASIC INFORMATION\n");
        result.append("───────────────────────────────\n");

        if (data.chineseName != null) {
            result.append("Chinese Name: ").append(data.chineseName).append("\n");
        }
        if (data.pinyinName != null) {
            result.append("Pinyin Name: ").append(data.pinyinName).append("\n");
        }
        if (data.firstName != null && data.lastName != null) {
            result.append("English Name: ").append(data.firstName).append(" ").append(data.lastName).append("\n");
        }

        result.append("Card Number: ").append(data.cardNumber != null ? data.cardNumber : data.documentNumber).append("\n");

        if (data.idNumber != null) {
            result.append("ID Number: ").append(data.idNumber).append("\n");
        }

        result.append("Nationality: ").append(data.nationality != null ? data.nationality : "CHN").append("\n");
        result.append("Gender: ").append(data.gender != null ? data.gender : "Unknown").append("\n");
        result.append("Date of Birth: ").append(formatDate(data.dateOfBirth)).append("\n");

        if (data.placeOfBirth != null) {
            result.append("Place of Birth: ").append(data.placeOfBirth).append("\n");
        }
        result.append("\n");

        result.append("───────────────────────────────\n");
        result.append("📍 ADDRESS INFORMATION\n");
        result.append("───────────────────────────────\n");

        if (data.registeredAddress != null) {
            result.append("Registered Address: ").append(data.registeredAddress).append("\n");
        }

        if (data.addressLines != null && !data.addressLines.isEmpty()) {
            for (String line : data.addressLines) {
                result.append("  ").append(line).append("\n");
            }
        }
        result.append("\n");

        result.append("───────────────────────────────\n");
        result.append("✈️ VALIDITY & DESTINATIONS\n");
        result.append("───────────────────────────────\n");

        result.append("Valid For:\n");
        if (data.validForHongKong) {
            result.append("  ✓ Hong Kong");
            if (data.hongKongValidity != null) {
                result.append(" (until ").append(data.hongKongValidity).append(")");
            }
            result.append("\n");
        }

        if (data.validForMacao) {
            result.append("  ✓ Macao");
            if (data.macaoValidity != null) {
                result.append(" (until ").append(data.macaoValidity).append(")");
            }
            result.append("\n");
        }

        if (!data.validForHongKong && !data.validForMacao) {
            result.append("  (No active destinations)\n");
        }

        result.append("Date of Expiry: ").append(formatDate(data.dateOfExpiry)).append("\n");
        result.append("\n");

        if (data.endorsements != null && !data.endorsements.isEmpty()) {
            result.append("───────────────────────────────\n");
            result.append("📝 ENDORSEMENTS (签注)\n");
            result.append("───────────────────────────────\n");

            for (int i = 0; i < data.endorsements.size(); i++) {
                EepData.EndorsementInfo endorsement = data.endorsements.get(i);
                result.append("Endorsement ").append(i + 1).append(":\n");
                result.append("  Type: ").append(endorsement.type).append("\n");
                result.append("  Destination: ").append(endorsement.destination).append("\n");
                result.append("  Valid: ").append(formatDate(endorsement.validFrom))
                        .append(" - ").append(formatDate(endorsement.validUntil)).append("\n");
                result.append("  Entries: ").append(endorsement.allowedEntries).append("\n");
                result.append("  Status: ").append(endorsement.isUsed ? "Used" : "Valid").append("\n");
                result.append("\n");
            }
        }

        if (data.endorsementType != null) {
            result.append("Current Endorsement Type: ").append(data.endorsementType).append("\n");
        }

        if (data.remainingEntries > 0) {
            result.append("Remaining Entries: ").append(data.remainingEntries).append("\n");
        }
        result.append("\n");

        result.append("───────────────────────────────\n");
        result.append("📋 ISSUANCE DETAILS\n");
        result.append("───────────────────────────────\n");

        if (data.issuingAuthority != null) {
            result.append("Issuing Authority: ").append(data.issuingAuthority).append("\n");
        }

        if (data.applicationLocation != null) {
            result.append("Application Location: ").append(data.applicationLocation).append("\n");
        }

        if (data.applicationDate != null) {
            result.append("Application Date: ").append(formatDate(data.applicationDate)).append("\n");
        }

        if (data.dateOfIssue != null) {
            result.append("Date of Issue: ").append(formatDate(data.dateOfIssue)).append("\n");
        }
        result.append("\n");

        if (!data.faceImages.isEmpty()) {
            result.append("📸 BIOMETRIC DATA\n");
            result.append("───────────────────────────────\n");
            result.append("Face Image: Available (").append(data.faceImages.size()).append(" photo(s))\n");
            imageFace.setImageBitmap(data.faceImages.get(0));

            if (data.hasFingerprints) {
                result.append("Fingerprints: ").append(data.fingerprints.size()).append(" scan(s)\n");
            }
            result.append("\n");
        }

        // ========== ADD SOD SECTION HERE ==========
        if (data.sodPresent) {
            result.append("───────────────────────────────\n");
            result.append("🔏 SOD (Security Object Document)\n");
            result.append("───────────────────────────────\n");

            result.append("Digest Algorithm: ").append(data.sodDigestAlgorithm != null ? data.sodDigestAlgorithm : "N/A").append("\n");
            result.append("Signature Algorithm: ").append(data.sodSignatureAlgorithm != null ? data.sodSignatureAlgorithm : "N/A").append("\n");

            if (data.sodLdsVersion != null) {
                result.append("LDS Version: ").append(data.sodLdsVersion).append("\n");
            }
            if (data.sodUnicodeVersion != null) {
                result.append("Unicode Version: ").append(data.sodUnicodeVersion).append("\n");
            }

            result.append("SOD Size: ").append(data.sodRawSize).append(" bytes\n");
            result.append("\n");

            // Display Data Group Hashes
            if (data.dataGroupHashes != null && !data.dataGroupHashes.isEmpty()) {
                result.append("📊 Data Group Hashes:\n");
                result.append("───────────────────────────────\n");

                // Sort by DG number for consistent display
                List<Integer> sortedDgs = new ArrayList<>(data.dataGroupHashes.keySet());
                Collections.sort(sortedDgs);

                for (Integer dgNum : sortedDgs) {
                    String hash = data.dataGroupHashes.get(dgNum);
                    String dgName = getDataGroupName(dgNum);

                    result.append("DG").append(dgNum);
                    if (dgName != null) {
                        result.append(" (").append(dgName).append(")");
                    }
                    result.append(":\n");

                    // Format hash with line breaks for readability
                    result.append("  ").append(formatHash(hash)).append("\n\n");
                }
            }
        }

        if (data.hasRfidChip) {
            result.append("🔐 SECURITY FEATURES\n");
            result.append("───────────────────────────────\n");
            result.append("RFID Chip: Present\n");

            if (data.hasValidSignature) {
                result.append("Digital Signature: Valid ✓\n");
            }

            result.append("Authentication: ").append(data.authenticationMethod != null ? data.authenticationMethod : "BAC").append("\n");
            result.append("\n");
        }

        result.append("═══════════════════════════════\n");
        result.append("✅ Read completed successfully\n");
        result.append("═══════════════════════════════");

        tvResult.setText(result.toString());
    }

    // Helper method to get human-readable DG names
    private String getDataGroupName(int dgNumber) {
        switch (dgNumber) {
            case 1: return "MRZ";
            case 2: return "Face Image";
            case 3: return "Fingerprints";
            case 4: return "Iris";
            case 5: return "Displayed Portrait";
            case 6: return "Reserved";
            case 7: return "Signature";
            case 8: return "Data Features";
            case 9: return "Structure Features";
            case 10: return "Substance Features";
            case 11: return "Additional Personal Details";
            case 12: return "Additional Document Details";
            case 13: return "Optional Details";
            case 14: return "Security Options";
            case 15: return "Active Authentication Public Key";
            case 16: return "Persons to Notify";
            default: return null;
        }
    }

    // Helper method to format long hash strings
    private String formatHash(String hash) {
        if (hash == null) return "N/A";

        // Insert space every 8 characters for readability
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < hash.length(); i++) {
            if (i > 0 && i % 8 == 0) {
                formatted.append(" ");
            }
            formatted.append(hash.charAt(i));
        }
        return formatted.toString();
    }

    private String formatDate(String yymmdd) {
        if (yymmdd == null || yymmdd.isEmpty()) {
            return "N/A";
        }

        // Remove any non-digit characters first
        String digits = yymmdd.replaceAll("[^0-9]", "");

        // Handle YYMMDD format
        if (digits.length() == 6) {
            try {
                String year = digits.substring(0, 2);
                String month = digits.substring(2, 4);
                String day = digits.substring(4, 6);

                int yy = Integer.parseInt(year);
                int fullYear = (yy > 50) ? (1900 + yy) : (2000 + yy);

                return day + "/" + month + "/" + fullYear;
            } catch (NumberFormatException e) {
                return yymmdd; // Return original if parsing fails
            }
        }

        // Handle YYYYMMDD format
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

        // Return original if format is unrecognized
        return yymmdd;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_CAMERA && resultCode == Activity.RESULT_OK && data != null) {
            String cardNum = data.getStringExtra("DOC_NUM");
            String dob = data.getStringExtra("DOB");
            String expiry = data.getStringExtra("EXPIRY");

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (cardNum != null && !cardNum.isEmpty()) {
                        edCardNum.setText(cardNum);
                    }
                    if (dob != null && !dob.isEmpty()) {
                        edBirthDate.setText(dob);
                    }
                    if (expiry != null && !expiry.isEmpty()) {
                        edExpiryDate.setText(expiry);
                    }

                    tvStatus.setText("✅ Data Scanned! Click 'Read NFC' button and tap permit.");
                    Toast.makeText(getContext(), "Data loaded! Now click 'Read NFC' button", Toast.LENGTH_SHORT).show();
                });
            }
        }
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