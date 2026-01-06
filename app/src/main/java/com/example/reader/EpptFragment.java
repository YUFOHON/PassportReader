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
import com.example.reader.models.PassportData;
import com.example.reader.readers.DocumentAuthData;
import com.google.android.material.textfield.TextInputEditText;

public class EpptFragment extends Fragment {

    private static final int REQUEST_CODE_CAMERA = 100;
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private static final String TAG = "EpptFragment";

    private TextInputEditText edDocNum, edBirthDate, edExpiryDate;
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
        View view = inflater.inflate(R.layout.fragment_eppt, container, false);

        // Initialize Views
        edDocNum = view.findViewById(R.id.ed_doc_number);
        edBirthDate = view.findViewById(R.id.ed_birth_date);
        edExpiryDate = view.findViewById(R.id.ed_expiry_date);
        tvStatus = view.findViewById(R.id.tv_status);
        tvResult = view.findViewById(R.id.tv_result);
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
            Intent intent = new Intent(getActivity(), CameraActivity.class);
            startActivityForResult(intent, REQUEST_CODE_CAMERA);
        });

        // Setup Read NFC Button
        btnReadNfc.setOnClickListener(v -> {
            startNfcReading();
        });

        return view;
    }

    /**
     * Initialize NFC adapter and pending intent
     */
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

        // Create pending intent for NFC
        Intent intent = new Intent(getActivity(), getActivity().getClass());
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(
                getActivity(),
                0,
                intent,
                PendingIntent.FLAG_MUTABLE
        );
    }

    /**
     * Enable NFC foreground dispatch
     */
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

    /**
     * Disable NFC foreground dispatch
     */
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
                        String docType = expectedType.name().replace("_", " ");
                        tvStatus.setText("📱 Reading " + docType + "... DO NOT MOVE");
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
                        // Disable NFC after successful read
                        disableNfcForegroundDispatch();
                        Toast.makeText(getContext(), "✅ Read complete! NFC disabled", Toast.LENGTH_SHORT).show();
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
                        // Disable NFC after error
                        disableNfcForegroundDispatch();
                    });
                }
            }
        });
    }

    private void startNfcReading() {
        String docNum = edDocNum.getText().toString().trim();
        String birthDate = edBirthDate.getText().toString().trim();
        String expiryDate = edExpiryDate.getText().toString().trim();

        if (docNum.isEmpty() || birthDate.isEmpty() || expiryDate.isEmpty()) {
            Toast.makeText(getContext(), "Please scan MRZ or enter data first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if NFC is available
        if (nfcAdapter == null) {
            Toast.makeText(getContext(), "NFC is not supported on this device", Toast.LENGTH_LONG).show();
            return;
        }

        if (!nfcAdapter.isEnabled()) {
            Toast.makeText(getContext(), "Please enable NFC in settings", Toast.LENGTH_LONG).show();
            return;
        }

        waitingForNfc = true;
        tvStatus.setText("📱 NFC Activated! Now tap document to back of phone...");
        tvResult.setText("");
        imageFace.setImageBitmap(null);

        // Enable NFC foreground dispatch
        enableNfcForegroundDispatch();

        Toast.makeText(getContext(), "NFC ready - Tap your document now", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_CAMERA && resultCode == Activity.RESULT_OK && data != null) {

            String docNum = data.getStringExtra("DOC_NUM");
            String dob = data.getStringExtra("DOB");
            String expiry = data.getStringExtra("EXPIRY");

            if (edDocNum == null || edBirthDate == null || edExpiryDate == null) {
                Toast.makeText(getContext(), "UI Error: Fields not initialized", Toast.LENGTH_LONG).show();
                return;
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    try {
                        if (docNum != null && !docNum.isEmpty()) {
                            edDocNum.setText(docNum);
                        }

                        if (dob != null && !dob.isEmpty()) {
                            edBirthDate.setText(dob);
                        }

                        if (expiry != null && !expiry.isEmpty()) {
                            edExpiryDate.setText(expiry);
                        }

                        tvStatus.setText("✅ MRZ Scanned! Click 'Read NFC' button and tap document.");
                        Toast.makeText(getContext(), "Data loaded! Now click 'Read NFC' button", Toast.LENGTH_SHORT).show();

                    } catch (Exception e) {
                        Log.e(TAG, "Exception setting text: " + e.getMessage());
                    }
                });
            }

        } else if (requestCode == REQUEST_CODE_CAMERA && resultCode == Activity.RESULT_CANCELED) {
            if (tvStatus != null) {
                tvStatus.setText("Scan cancelled. Try again or enter manually.");
            }
        }
    }

    public void handleNfcIntent(Intent intent) {
        if (!waitingForNfc) {
            Toast.makeText(getContext(), "Please click 'Read NFC' button first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

            String docNum = edDocNum.getText().toString().trim();
            String birthDate = edBirthDate.getText().toString().trim();
            String expiryDate = edExpiryDate.getText().toString().trim();

            // Create authentication data
            DocumentAuthData authData = new DocumentAuthData(docNum, birthDate, expiryDate);

            // Read document (auto-detect type or specify PASSPORT)
            documentReader.readDocument(tag, authData, DocumentData.DocumentType.PASSPORT);
        }
    }

    /**
     * Display document data based on type
     */
    private void displayDocumentData(DocumentData data) {
        if (data instanceof PassportData) {
            displayPassportData((PassportData) data);
        } else {
            // Handle other document types in the future
            displayGenericDocumentData(data);
        }
    }

    /**
     * Display generic document data
     */
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

        result.append("───────────────────────────────\n");
        result.append("🔐 SECURITY OBJECT (SOD)\n");
        result.append("───────────────────────────────\n");

        if (data.rawSODData != null) {
            result.append("Select Status: ").append(data.hasValidSignature ? "Present" : "Error").append("\n");
            result.append("Signer (Issuer): ").append(data.signingCountry != null ? data.signingCountry : "Unknown").append("\n");
            result.append("Raw Size: ").append(data.rawSODData.length).append(" bytes\n");

            if (data.dataGroupHashes != null && !data.dataGroupHashes.isEmpty()) {
                result.append("\nIntegrity Hashes (DG Hashes):\n");
                java.util.TreeMap<Integer, byte[]> sortedHashes = new java.util.TreeMap<>(data.dataGroupHashes);

                for (java.util.Map.Entry<Integer, byte[]> entry : sortedHashes.entrySet()) {
                    int dgNum = entry.getKey();
                    String hashHex = bytesToHex(entry.getValue());
                    if (hashHex.length() > 20) {
                        hashHex = hashHex.substring(0, 20) + "...";
                    }
                    result.append("  • DG").append(dgNum).append(": ").append(hashHex).append("\n");
                }
            } else {
                result.append("No Data Group hashes found.\n");
            }
        } else {
            result.append("⚠️ SOD Data was not read.\n");
        }
        result.append("\n");

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
        if (data.optionalData1 != null && !data.optionalData1.isEmpty()) {
            result.append("Optional Data: ").append(data.optionalData1).append("\n");
        }
        result.append("\n");

        result.append("📸 FACIAL IMAGE (DG2)\n");
        result.append("───────────────────────────────\n");
        if (!data.faceImages.isEmpty()) {
            result.append("Images: ").append(data.faceImages.size()).append(" photo(s)\n");
            for (int i = 0; i < data.faceImageMimeTypes.size(); i++) {
                result.append("  Format ").append(i + 1).append(": ").append(data.faceImageMimeTypes.get(i)).append("\n");
            }
            imageFace.setImageBitmap(data.faceImages.get(0));
        } else {
            result.append("No face image available\n");
        }
        result.append("\n");

        if (data.fullName != null || data.placeOfBirth != null || data.address != null) {
            result.append("ℹ️ ADDITIONAL DETAILS (DG11)\n");
            result.append("───────────────────────────────\n");
            if (data.fullName != null) {
                result.append("Full Name: ").append(data.fullName).append("\n");
            }
            if (data.personalNumber != null) {
                result.append("Personal Number: ").append(data.personalNumber).append("\n");
            }
            if (data.placeOfBirth != null && !data.placeOfBirth.isEmpty()) {
                result.append("Place of Birth: ").append(String.join(", ", data.placeOfBirth)).append("\n");
            }
            if (data.address != null && !data.address.isEmpty()) {
                result.append("Address: ").append(String.join(", ", data.address)).append("\n");
            }
            if (data.telephone != null) {
                result.append("Telephone: ").append(data.telephone).append("\n");
            }
            if (data.profession != null) {
                result.append("Profession: ").append(data.profession).append("\n");
            }
            result.append("\n");
        }

        if (data.issuingAuthority != null || data.dateOfIssue != null) {
            result.append("📋 DOCUMENT DETAILS (DG12)\n");
            result.append("───────────────────────────────\n");
            if (data.issuingAuthority != null) {
                result.append("Issuing Authority: ").append(data.issuingAuthority).append("\n");
            }
            if (data.dateOfIssue != null) {
                result.append("Date of Issue: ").append(data.dateOfIssue).append("\n");
            }
            if (data.endorsementsAndObservations != null) {
                result.append("Endorsements: ").append(data.endorsementsAndObservations).append("\n");
            }
            result.append("\n");
        }

        if (data.hasFingerprintData || data.hasIrisData) {
            result.append("👆 BIOMETRIC DATA\n");
            result.append("───────────────────────────────\n");
            if (data.hasFingerprintData) {
                result.append("Fingerprints (DG3): ").append(data.fingerprints.size()).append(" scan(s)\n");
            }
            if (data.hasIrisData) {
                result.append("Iris Scans (DG4): ").append(data.irisScans.size()).append(" scan(s)\n");
                for (PassportData.IrisData iris : data.irisScans) {
                    result.append("  - ").append(iris.eyeLabel).append(" (").append(iris.imageFormat).append(")\n");
                }
            }
            result.append("\n");
        }

        result.append("🔐 SECURITY INFORMATION\n");
        result.append("───────────────────────────────\n");
        result.append("Authentication: ").append(data.authenticationMethod).append("\n");
        result.append("Chip Auth: ").append(data.hasChipAuthentication ? "Yes" : "No").append("\n");
        result.append("Active Auth: ").append(data.hasActiveAuthentication ? "Yes ✓" : "No").append("\n");
        if (data.hasActiveAuthentication && data.activeAuthenticationPerformed) {
            result.append("  → Verified: ✓\n");
        }
        result.append("Digital Signature: ").append(data.hasValidSignature ? "Valid ✓" : "Not verified").append("\n");
        if (data.signingCountry != null) {
            result.append("  Signed by: ").append(data.signingCountry).append("\n");
        }
        result.append("\n");

        result.append("📊 DATA GROUPS PRESENT\n");
        result.append("───────────────────────────────\n");
        result.append("Available DGs: ");
        for (Integer dg : data.availableDataGroups) {
            result.append("DG").append(dg).append(" ");
        }
        result.append("\n\n");

        if (!data.supportedSecurityProtocols.isEmpty()) {
            result.append("Protocols: ").append(String.join(", ", data.supportedSecurityProtocols)).append("\n");
        }

        result.append("\n═══════════════════════════════\n");
        result.append("✅ Read completed successfully\n");
        result.append("═══════════════════════════════");

        tvResult.setText(result.toString());
    }

    private String formatDate(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6) {
            return yymmdd;
        }

        String year = yymmdd.substring(0, 2);
        String month = yymmdd.substring(2, 4);
        String day = yymmdd.substring(4, 6);

        int yy = Integer.parseInt(year);
        int fullYear = (yy > 50) ? (1900 + yy) : (2000 + yy);

        return day + "/" + month + "/" + fullYear;
    }

    public static String bytesToHex(byte[] bytes) {
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
        // Re-initialize NFC if needed (in case settings changed)
        if (nfcAdapter == null) {
            initializeNfc();
        }
        // Re-enable NFC if we were waiting for it
        if (waitingForNfc) {
            enableNfcForegroundDispatch();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Disable NFC when fragment is paused
        disableNfcForegroundDispatch();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (documentReader != null) {
            documentReader.cancelRead();
        }
        // Make sure NFC is disabled
        disableNfcForegroundDispatch();
    }
}