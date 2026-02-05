package com.example.reader.models;

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

    // Security features
    public boolean hasRfidChip;

    // SOD (Security Object Document) data
    public String sodDigestAlgorithm;
    public String sodSignatureAlgorithm;
    public Map<Integer, String> dataGroupHashes;  // DG number -> hash hex string
    public String sodLdsVersion;
    public String sodUnicodeVersion;
    public boolean sodPresent = false;
    public int sodRawSize;

    public EepData() {
        super(DocumentType.EEP);
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
        summary.append("EEP Card: ").append(chineseName != null ? chineseName : "Unknown");
        summary.append(" #").append(cardNumber != null ? cardNumber : "N/A");


        // Add validity if available
        if (dateOfExpiry != null) {
            summary.append(" Exp: ").append(dateOfExpiry);
        }

        // Add data groups info
        if (dataGroupHashes != null) {
            summary.append(" [").append(dataGroupHashes.size()).append(" DGs");
            if (sodPresent) {
                summary.append(", SOD✓");
            }
            summary.append("]");
        }

        return summary.toString();
    }


}