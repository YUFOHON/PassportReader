package com.example.reader;

import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_CAMERA = 100;
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    private NfcAdapter nfcAdapter;

    private TextInputEditText edDocNum, edBirthDate, edExpiryDate;
    private TextView tvStatus, tvResult;
    private ImageView imageFace;
    private Button btnScan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Customize ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("📘 Passport NFC Reader");
            getSupportActionBar().setBackgroundDrawable(
                    getResources().getDrawable(android.R.color.holo_blue_dark)
            );
        }

        // Initialize Views
        edDocNum = findViewById(R.id.ed_doc_number);
        edBirthDate = findViewById(R.id.ed_birth_date);
        edExpiryDate = findViewById(R.id.ed_expiry_date);
        tvStatus = findViewById(R.id.tv_status);
        tvResult = findViewById(R.id.tv_result);
        imageFace = findViewById(R.id.image_face);
        btnScan = findViewById(R.id.btn_scan);

        // Verify initialization
        if (edDocNum == null) {
            Log.e("@@>>", "CRITICAL: edDocNum is NULL!");
        }
        if (edBirthDate == null) {
            Log.e("@@>>", "CRITICAL: edBirthDate is NULL!");
        }
        if (edExpiryDate == null) {
            Log.e("@@>>", "CRITICAL: edExpiryDate is NULL!");
        }

        // Setup Camera Button
        btnScan.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CameraActivity.class);
            startActivityForResult(intent, REQUEST_CODE_CAMERA);
        });

        // Setup NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC not supported", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.d("@@>>", "========== onActivityResult START ==========");
        Log.d("@@>>", "Request Code: " + requestCode);
        Log.d("@@>>", "Result Code: " + resultCode);

        if (requestCode == REQUEST_CODE_CAMERA && resultCode == RESULT_OK && data != null) {

            String docNum = data.getStringExtra("DOC_NUM");
            String dob = data.getStringExtra("DOB");
            String expiry = data.getStringExtra("EXPIRY");

            Log.d("@@>>", "Extracted DOC_NUM: '" + docNum + "'");
            Log.d("@@>>", "Extracted DOB: '" + dob + "'");
            Log.d("@@>>", "Extracted EXPIRY: '" + expiry + "'");

            // Check if EditTexts are still valid
            if (edDocNum == null || edBirthDate == null || edExpiryDate == null) {
                Log.e("@@>>", "ERROR: EditText fields are NULL!");
                Toast.makeText(this, "UI Error: Fields not initialized", Toast.LENGTH_LONG).show();
                return;
            }

            // Set values with runOnUiThread to ensure UI update
            runOnUiThread(() -> {
                try {
                    if (docNum != null && !docNum.isEmpty()) {
                        edDocNum.setText(docNum);
                        edDocNum.requestFocus();
                        Log.d("@@>>", "✓ Set docNum: " + docNum);
                        Log.d("@@>>", "  Verify getText(): '" + edDocNum.getText().toString() + "'");
                    }

                    if (dob != null && !dob.isEmpty()) {
                        edBirthDate.setText(dob);
                        Log.d("@@>>", "✓ Set dob: " + dob);
                        Log.d("@@>>", "  Verify getText(): '" + edBirthDate.getText().toString() + "'");
                    }

                    if (expiry != null && !expiry.isEmpty()) {
                        edExpiryDate.setText(expiry);
                        Log.d("@@>>", "✓ Set expiry: " + expiry);
                        Log.d("@@>>", "  Verify getText(): '" + edExpiryDate.getText().toString() + "'");
                    }

                    // Force refresh
                    edDocNum.invalidate();
                    edBirthDate.invalidate();
                    edExpiryDate.invalidate();

                    tvStatus.setText("✅ MRZ Scanned! Now tap passport to back of phone.");
                    Toast.makeText(this, "Data loaded successfully!", Toast.LENGTH_SHORT).show();

                } catch (Exception e) {
                    Log.e("@@>>", "Exception setting text: " + e.getMessage());
                    e.printStackTrace();
                }
            });

        } else if (requestCode == REQUEST_CODE_CAMERA && resultCode == RESULT_CANCELED) {
            tvStatus.setText("Scan cancelled. Try again or enter manually.");
        }

        Log.d("@@>>", "========== onActivityResult END ==========");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            Intent intent = new Intent(this, this.getClass());
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_MUTABLE
            );

            String[][] techList = new String[][]{{IsoDep.class.getName()}};
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, techList);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

            String docNum = edDocNum.getText().toString();
            String birthDate = edBirthDate.getText().toString();
            String expiryDate = edExpiryDate.getText().toString();

            if (docNum.isEmpty() || birthDate.isEmpty() || expiryDate.isEmpty()) {
                Toast.makeText(this, "Please scan MRZ or enter data first", Toast.LENGTH_SHORT).show();
                return;
            }

            tvStatus.setText("Status: Reading passport... DO NOT MOVE");
            tvResult.setText("");
            imageFace.setImageBitmap(null);

            new Thread(() -> {
                try {
                    PassportReader reader = new PassportReader();
                    PassportReader.PassportData result = reader.readPassport(tag, docNum, birthDate, expiryDate);

                    Log.d("@@>> NFC", "Passport read completed successfully");
                    Log.d("@@>> NFC", "Raw passport data: " + result.toString());

                    runOnUiThread(() -> displayPassportData(result));
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        tvStatus.setText("❌ Error: " + e.getMessage());
                        Toast.makeText(MainActivity.this, "Read failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }
    }

    private void displayPassportData(PassportReader.PassportData data) {
        tvStatus.setText("✅ Passport Read Complete!");
        StringBuilder result = new StringBuilder();
        result.append("═══════════════════════════════\n");
        result.append("📘 PASSPORT INFORMATION\n");
        result.append("═══════════════════════════════\n\n");
        // --- SOD SECTION START ---
        result.append("───────────────────────────────\n");
        result.append("🔐 SECURITY OBJECT (SOD)\n");
        result.append("───────────────────────────────\n");

        if (data.rawSODData != null) {
            result.append("Select Status: ").append(data.hasValidSignature ? "Present" : "Error").append("\n");
            result.append("Signer (Issuer): ").append(data.signingCountry != null ? data.signingCountry : "Unknown").append("\n");
            result.append("Raw Size: ").append(data.rawSODData.length).append(" bytes\n");

            // Display the hashes found in the SOD
            if (data.dataGroupHashes != null && !data.dataGroupHashes.isEmpty()) {
                result.append("\nIntegrity Hashes (DG Hashes):\n");
                // Sort keys for cleaner display (DG1, DG2, etc.)
                java.util.TreeMap<Integer, byte[]> sortedHashes = new java.util.TreeMap<>(data.dataGroupHashes);

                for (java.util.Map.Entry<Integer, byte[]> entry : sortedHashes.entrySet()) {
                    int dgNum = entry.getKey();
                    String hashHex = bytesToHex(entry.getValue());
                    // Truncate hash for display if it's too long
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
        result.append("Issuing Country: ").append(data.issuingState).append("\n");
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
                for (PassportReader.IrisData iris : data.irisScans) {
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
}