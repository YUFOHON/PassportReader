package com.example.reader.readers;

import static com.example.reader.readers.eep.HexUtils.bytesToHex;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;

import com.example.reader.models.DocumentData;
import com.example.reader.models.EepData;
import com.example.reader.readers.eep.*;

import net.sf.scuba.smartcards.CardService;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;
import org.jmrtd.lds.SODFile;
import org.jmrtd.lds.icao.*;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader for Chinese Exit-Entry Permits (往来港澳通行证)
 *
 * Supports:
 * - Hong Kong/Macau Travel Permit (CS document code)
 * - Taiwan Travel Permit (CD document code)
 */
public class EepDocumentReader implements IDocumentReader {

    private static final String TAG = "@@>> EepDocumentReader";

    private final EepMrzParser mrzParser;
    private final EmrtdAuthenticator authenticator;
    private final ChineseNameDecoder nameDecoder;

    public EepDocumentReader() {
        this.mrzParser = new EepMrzParser();
        this.authenticator = new EmrtdAuthenticator();
        this.nameDecoder = new ChineseNameDecoder();
    }

    @Override
    public DocumentData readDocument(Tag tag, DocumentAuthData authData,ProgressCallback progressCallback) throws Exception {
        validateInputs(tag, authData);
        if (progressCallback != null) {
            progressCallback.onProgress("Starting document read...", 0);
        }
        ChipReadResult chipData = readChip(tag, authData,progressCallback);

        if (progressCallback != null) {
            progressCallback.onProgress("Processing data...", 95);
        }

        DocumentData result = mapToEepData(chipData);

        if (progressCallback != null) {
            progressCallback.onProgress("Complete", 100);
        }

        return result;
    }


    @Override
    public DocumentData readDocument(Tag tag, DocumentAuthData authData) throws Exception {
        validateInputs(tag, authData);

        ChipReadResult chipData = readChip(tag, authData,null);
        return mapToEepData(chipData);
    }

    @Override
    public boolean canRead(Tag tag) {
        return tag != null && IsoDep.get(tag) != null;
    }

    @Override
    public DocumentData.DocumentType[] getSupportedTypes() {
        return new DocumentData.DocumentType[] { DocumentData.DocumentType.EEEP };
    }

    @Override
    public String getReaderName() {
        return "EepDocumentReader";
    }

    // ========== Private Implementation ==========

    private void validateInputs(Tag tag, DocumentAuthData authData) {
        if (tag == null) {
            throw new IllegalArgumentException("NFC tag is null");
        }
        if (authData == null || !authData.isValid()) {
            throw new IllegalArgumentException("Invalid authentication data");
        }
    }

    private ChipReadResult readChip(Tag tag, DocumentAuthData authData,ProgressCallback progressCallback) throws Exception {
        String docNumber = normalizeDocNumber(authData.getDocumentNumber());
        String birthDate = normalizeDateYYMMDD(authData.getDateOfBirth());
        String expiryDate = normalizeDateYYMMDD(authData.getDateOfExpiry());

        validateDates(birthDate, expiryDate);

        if (progressCallback != null) {
            progressCallback.onProgress("Connecting to chip...", 5);
        }

        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            throw new Exception("Tag does not support ISO-DEP");
        }

        isoDep.setTimeout(EepConstants.ISO_DEP_TIMEOUT_MS);
        isoDep.connect();

        if (progressCallback != null) {
            progressCallback.onProgress("Connected to chip", 10);
        }


        try {
            return performChipRead(isoDep, docNumber, birthDate, expiryDate, progressCallback);
        } finally {
            closeQuietly(isoDep);
        }
    }

    private ChipReadResult performChipRead(
            IsoDep isoDep,
            String docNumber,
            String birthDate,
            String expiryDate,
            ProgressCallback progressCallback) throws Exception {

        CardService cardService = CardService.getInstance(isoDep);
//        rawService.open();

//        CardService patchedService = new PatchedCardService(rawService);
//        PassportService passportService = createPassportService(patchedService);
        PassportService passportService = new PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,  // isSFIEnabled
                false   // shouldCheckMAC
        );
        try {
            if (progressCallback != null) {
                progressCallback.onProgress("Opening passport service...", 15);
            }
            passportService.open();
            passportService.sendSelectApplet(false);

            if (progressCallback != null) {
                progressCallback.onProgress("Authenticating...", 20);
            }

            // Authenticate
            BACKey bacKey = new BACKey(docNumber, birthDate, expiryDate);
            EmrtdAuthenticator.AuthResult authResult = authenticator.authenticate(passportService, bacKey);

            if (!authResult.success) {
                throw new Exception("Authentication failed: " + authResult.errorMessage);
            }
            if (progressCallback != null) {
                progressCallback.onProgress("Authentication successful", 30);
            }
            // Read data groups
            return readDataGroups(passportService, authResult.method, progressCallback);

        } finally {
            closeQuietly(passportService);
//            closeQuietly(patchedService);
//            closeQuietly(rawService);
        }
    }

    private PassportService createPassportService(CardService cardService) {
        return new PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,  // isSFIEnabled
                false   // shouldCheckMAC
        );
    }

    private ChipReadResult readDataGroups(
            PassportService service,
            EmrtdAuthenticator.AuthMethod authMethod,
            ProgressCallback progressCallback) throws Exception {

        ChipReadResult result = new ChipReadResult();
        result.authMethod = authMethod;

        DataGroupReader dgReader = new DataGroupReader(service);

        // Read SOD first
        if (progressCallback != null) {
            progressCallback.onProgress("Reading security data (SOD)...", 35);
        }

        // Read SOD first to get available data groups
        result.sodData = readSOD(service);
        if (result.sodData != null && result.sodData.dataGroupHashes != null) {
            result.availableDataGroups = new ArrayList<>(result.sodData.dataGroupHashes.keySet());
        }

        // Read DG1 - MRZ
        if (progressCallback != null) {
            progressCallback.onProgress("Reading document data (DG1)...", 45);
        }

        // Read mandatory groups
        DG1File dg1 = dgReader.readDG1();
        result.mrzData = parseMrz(dg1);

        if (progressCallback != null) {
            progressCallback.onProgress("Reading photo (DG2)...", 55);
        }
        result.faceImages = dgReader.readDG2();

        int currentProgress = 60;

        if (isAvailable(result, 11)) {
            if (progressCallback != null) {
                progressCallback.onProgress("Reading additional data (DG11)...", currentProgress);
            }
            result.dg11 = dgReader.readDG11();
            currentProgress += 8;
        }

        if (isAvailable(result, 12)) {
            if (progressCallback != null) {
                progressCallback.onProgress("Reading issuing data (DG12)...", currentProgress);
            }
            result.dg12 = dgReader.readDG12();
            currentProgress += 8;
        }

        if (isAvailable(result, 14)) {
            if (progressCallback != null) {
                progressCallback.onProgress("Reading security features (DG14)...", currentProgress);
            }
            result.dg14 = dgReader.readDG14();
            currentProgress += 7;
        }

        if (isAvailable(result, 15)) {
            if (progressCallback != null) {
                progressCallback.onProgress("Reading public key (DG15)...", currentProgress);
            }
            result.dg15 = dgReader.readDG15();
            currentProgress += 7;
        }

        if (progressCallback != null) {
            progressCallback.onProgress("Data groups read successfully", 90);
        }

        return result;
    }

    private SodData readSOD(PassportService service) {
        try {
            byte[] sodBytes = StreamUtils.readAllBytes(
                    service.getInputStream(PassportService.EF_SOD)
            );

            SODFile sodFile = new SODFile(new ByteArrayInputStream(sodBytes));

            SodData data = new SodData();
            data.rawBytes = sodBytes;
            data.dataGroupHashes = sodFile.getDataGroupHashes();
            data.digestAlgorithm = sodFile.getDigestAlgorithm();
            data.signatureAlgorithm = sodFile.getDigestEncryptionAlgorithm();
            data.isValid = true;

            // Try to get LDS and Unicode versions
            try {
                data.ldsVersion = sodFile.getLDSVersion();
                data.unicodeVersion = sodFile.getUnicodeVersion();
            } catch (Exception e) {
                Log.d(TAG, "LDS/Unicode version not available");
            }

            // Log for debugging
            logSodHashData(sodFile, data);

            return data;

        } catch (Exception e) {
            Log.e(TAG, "Failed to read SOD: " + e.getMessage());
            return null;
        }
    }

    private void logSodHashData(SODFile sodFile, SodData data) {
        Log.d(TAG, "========== SOD Hash Data ==========");
        Log.d(TAG, "Digest Algorithm: " + data.digestAlgorithm);
        Log.d(TAG, "Signature Algorithm: " + data.signatureAlgorithm);

        if (data.dataGroupHashes != null) {
            Log.d(TAG, "Number of Data Group Hashes: " + data.dataGroupHashes.size());

            for (Map.Entry<Integer, byte[]> entry : data.dataGroupHashes.entrySet()) {
                int dgNumber = entry.getKey();
                byte[] hash = entry.getValue();
                String hashHex = bytesToHex(hash);

                Log.d(TAG, "DG" + dgNumber + " Hash: " + hashHex);
            }
        }
        Log.d(TAG, "====================================");
    }

    private EepMrzParser.ParseResult parseMrz(DG1File dg1) {
        String rawMrz = dg1.toString();
        Log.d(TAG, "Raw DG1: " + rawMrz);

        String mrzString = extractMrzFromDg1(rawMrz);
        Log.d(TAG, "mrzString: " + rawMrz);

        Log.d(TAG, "isChineseExitEntryPermit : " + mrzParser.isChineseExitEntryPermit(mrzString));

        if (mrzParser.isChineseExitEntryPermit(mrzString)) {
            Log.d(TAG, "isChineseExitEntryPermit : " + rawMrz);

            return mrzParser.parse(mrzString);
        }

        // Fallback for standard passports
        return parseStandardMrz(dg1.getMRZInfo());
    }

    private EepMrzParser.ParseResult parseStandardMrz(MRZInfo mrz) {
        EepMrzParser.ParseResult result = new EepMrzParser.ParseResult();

        result.documentCode = mrz.getDocumentCode();
        result.cardNumber = mrz.getDocumentNumber();
        result.issuingState = mrz.getIssuingState();
        result.nationality = mrz.getNationality();
        result.dateOfBirth = mrz.getDateOfBirth();
        result.dateOfExpiry = mrz.getDateOfExpiry();
        result.gender = String.valueOf(mrz.getGender());
        result.lastName = mrz.getPrimaryIdentifier().replace("<", " ").trim();
        result.firstName = mrz.getSecondaryIdentifier().replace("<", " ").trim();

        return result;
    }

    private EepData mapToEepData(ChipReadResult chipData) {
        EepData out = new EepData();

        out.documentType = DocumentData.DocumentType.EEEP;

        // Map MRZ data
        EepMrzParser.ParseResult mrz = chipData.mrzData;
        if (mrz != null) {
            out.documentCode = mrz.documentCode;
            out.documentNumber = mrz.cardNumber;
            out.cardNumber = cleanCardNumber(mrz.cardNumber);
            out.issuingCountry = mrz.issuingState;
            out.nationality = mrz.nationality;
            out.dateOfBirth = mrz.dateOfBirth;
            out.dateOfExpiry = mrz.dateOfExpiry;
            out.gender = mrz.gender;
            out.firstName = mrz.firstName;
            out.lastName = mrz.lastName;
            out.chineseName = mrz.chineseName;
            out.pinyinName = buildPinyinName(mrz.lastName,mrz.firstName);
            out.placeOfBirth = mrz.placeOfBirth;
        }

        // Apply DG11 overrides (more accurate name data)
        if (chipData.dg11 != null) {
            applyDg11Data(out, chipData.dg11);
        }

        // Apply DG12 data
        if (chipData.dg12 != null) {
            out.issuingAuthority = chipData.dg12.getIssuingAuthority();
            out.dateOfIssue = chipData.dg12.getDateOfIssue();
        }

        // Face images
        for (DataGroupReader.FaceImageResult face : chipData.faceImages) {
            out.faceImages.add(face.bitmap);
            out.faceImageMimeTypes.add(face.mimeType);
        }

        // Security features
        out.hasRfidChip = true;
        out.hasValidSignature = chipData.sodData != null && chipData.sodData.isValid;
        out.authenticationMethod = chipData.authMethod != null ? chipData.authMethod.name() : null;

        // ========== MAP SOD DATA ==========
        if (chipData.sodData != null) {
            applySodData(out, chipData.sodData);
        }

        // Full name logic
        out.fullName = out.chineseName != null ? out.chineseName : out.pinyinName;

        return out;
    }

    private void applySodData(EepData out, SodData sodData) {
        out.sodPresent = true;
        out.sodDigestAlgorithm = sodData.digestAlgorithm;
        out.sodSignatureAlgorithm = sodData.signatureAlgorithm;
        out.sodLdsVersion = sodData.ldsVersion;
        out.sodUnicodeVersion = sodData.unicodeVersion;

        if (sodData.rawBytes != null) {
            out.sodRawSize = sodData.rawBytes.length;
        }

        // Convert hash bytes to hex strings
        if (sodData.dataGroupHashes != null) {
            out.dataGroupHashes = new HashMap<>();
            for (Map.Entry<Integer, byte[]> entry : sodData.dataGroupHashes.entrySet()) {
                out.dataGroupHashes.put(entry.getKey(), bytesToHex(entry.getValue()));
            }
        }
    }

    private void applyDg11Data(EepData out, DG11File dg11) {
        try {
            String fullName = dg11.getNameOfHolder();
            if (fullName != null && !fullName.isEmpty()) {
                if (ChineseNameDecoder.containsChinese(fullName)) {
                    out.chineseName = fullName;
                }
            }

            List<String> placeOfBirth = dg11.getPlaceOfBirth();
            if (placeOfBirth != null && !placeOfBirth.isEmpty()) {
                out.placeOfBirth = String.join(", ", placeOfBirth);
            }

        } catch (Exception e) {
            Log.w(TAG, "Error applying DG11 data: " + e.getMessage());
        }
    }

    // ========== Helper Methods ==========

    private String normalizeDocNumber(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private String normalizeDateYYMMDD(String s) {
        if (s == null) return "";
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.length() == 8) {
            return digits.substring(2);  // YYYYMMDD -> YYMMDD
        }
        return digits;
    }

    private void validateDates(String birthDate, String expiryDate) {
        if (birthDate.length() != 6 || expiryDate.length() != 6) {
            throw new IllegalArgumentException(
                    "Dates must be YYMMDD format (got DOB=" + birthDate + ", DOE=" + expiryDate + ")"
            );
        }
    }

    private String cleanCardNumber(String cardNumber) {
        if (cardNumber == null) return null;
        return cardNumber.replaceAll("\\s+", "").replaceAll("<+$", "");
    }

    private String buildPinyinName(String firstName, String lastName) {
        String name = (safe(firstName) + " " + safe(lastName)).trim();
        return name.isEmpty() ? null : name;
    }

    private boolean isAvailable(ChipReadResult result, int dgNumber) {
        return result.availableDataGroups != null
                && result.availableDataGroups.contains(dgNumber);
    }

    private String extractMrzFromDg1(String rawDg1) {
        // Implementation moved from original class
        // ... (same logic as before)
        // Remove "DG1File " prefix if present
        if (rawDg1.startsWith("DG1File ")) {
            rawDg1 = rawDg1.substring(8).trim();  // "DG1File " is 8 characters
        }
        return rawDg1.replaceAll("[\\r\\n\\s]+", "");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private void closeQuietly(Object closeable) {
        if (closeable == null) return;

        try {
            if (closeable instanceof CardService) {
                ((CardService) closeable).close();
            } else if (closeable instanceof IsoDep) {
                ((IsoDep) closeable).close();
            } else if (closeable instanceof AutoCloseable) {
                ((AutoCloseable) closeable).close();
            }
        } catch (Exception ignored) {
            // Ignore close errors
        }
    }

    // ========== Inner Classes ==========

    private static class ChipReadResult {
        EmrtdAuthenticator.AuthMethod authMethod;
        List<Integer> availableDataGroups = new ArrayList<>();
        SodData sodData;
        EepMrzParser.ParseResult mrzData;
        List<DataGroupReader.FaceImageResult> faceImages = new ArrayList<>();
        DG11File dg11;
        DG12File dg12;
        DG14File dg14;
        DG15File dg15;
    }

    private static class SodData {
        byte[] rawBytes;
        Map<Integer, byte[]> dataGroupHashes;
        String digestAlgorithm;
        String signatureAlgorithm;
        String ldsVersion;
        String unicodeVersion;
        boolean isValid;
    }
}