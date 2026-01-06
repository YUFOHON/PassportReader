package com.example.reader.models;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hong Kong and Macao Travel Permit (Two-way Permit / EEP) data model
 */
public class EepData extends DocumentData {

    // EEP-specific fields
    public String chineseName;          // Chinese name (in Chinese characters)
    public String pinyinName;           // Name in Pinyin
    public String cardNumber;           // Card number (similar to documentNumber)
    public String holderType;           // Holder type (e.g., Mainland resident)
    public String idNumber;             // Mainland ID card number

    // Address information
    public String registeredAddress;    // Hukou/registered address
    public List<String> addressLines = new ArrayList<>();

    // Valid destinations
    public boolean validForHongKong;
    public boolean validForMacao;
    public String hongKongValidity;     // Hong Kong validity period
    public String macaoValidity;        // Macao validity period

    // Endorsement information (签注)
    public List<EndorsementInfo> endorsements = new ArrayList<>();

    // Validity status
    public int remainingEntries;        // Remaining number of entries allowed
    public String endorsementType;      // Type of endorsement (individual/group, etc.)

    // Biometric data (EEP-specific)
    public boolean hasFingerprints;
    public List<FingerprintData> fingerprints = new ArrayList<>();

    // Security features
    public boolean hasRfidChip;
    public byte[] chipData;
    public Map<String, byte[]> dataElements = new HashMap<>();

    // Application information
    public String applicationLocation;   // Where the permit was issued
    public String applicationDate;       // Application date

    // SOD (Security Object Document) data
    public String sodDigestAlgorithm;
    public String sodSignatureAlgorithm;
    public Map<Integer, String> dataGroupHashes;  // DG number -> hash hex string
    public String sodLdsVersion;
    public String sodUnicodeVersion;
    public boolean sodPresent = false;
    public int sodRawSize;

    public EepData() {
        super(DocumentType.EEEP);
        this.documentCode = "EEP";
    }

    @Override
    public boolean isValid() {
        return cardNumber != null && !cardNumber.isEmpty()
                && chineseName != null && !chineseName.isEmpty()
                && dateOfExpiry != null && !dateOfExpiry.isEmpty();
    }

    @Override
    public String getSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("HK/Macao Permit: ");
        summary.append(chineseName != null ? chineseName : pinyinName != null ? pinyinName : "Unknown Name");
        summary.append(" (").append(cardNumber).append(")");

        if (validForHongKong && validForMacao) {
            summary.append(" - HK & Macao");
        } else if (validForHongKong) {
            summary.append(" - HK only");
        } else if (validForMacao) {
            summary.append(" - Macao only");
        }

        return summary.toString();
    }

    /**
     * Endorsement information (签注)
     */
    public static class EndorsementInfo {
        public String type;              // Individual (G), Group (L), Business (S), etc.
        public String destination;       // HK or Macao
        public String validFrom;
        public String validUntil;
        public int allowedEntries;       // Number of entries allowed
        public boolean isUsed;
        public String usageHistory;      // Entry/exit records
    }

    /**
     * Fingerprint data for EEP
     */
    public static class FingerprintData {
        public byte[] imageData;
        public String fingerPosition;    // Left/Right, Thumb/Index/etc.
        public String imageFormat;
        public int quality;
        public int width;
        public int height;
    }
}