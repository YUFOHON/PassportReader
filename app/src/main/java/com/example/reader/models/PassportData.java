package com.example.reader.models;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Passport-specific document data
 */
public class PassportData extends DocumentData {

    // Passport-specific fields
    public String personalNumber;
    public List<String> address;
    public String telephone;
    public String profession;
    public String optionalData1;
    public String optionalData2;

    // Biometric data (passport-specific)
    public boolean hasFingerprintData;
    public boolean hasIrisData;
    public List<FingerprintData> fingerprints = new ArrayList<>();
    public List<IrisData> irisScans = new ArrayList<>();

    // Security features
    public boolean hasChipAuthentication;
    public boolean hasActiveAuthentication;
    public boolean activeAuthenticationPerformed;
    public String signingCountry;
    public byte[] rawSODData;
    public Map<Integer, byte[]> dataGroupHashes = new HashMap<>();
    public List<Integer> availableDataGroups = new ArrayList<>();
    public List<String> supportedSecurityProtocols = new ArrayList<>();

    // Document details
    public String endorsementsAndObservations;

    public PassportData() {
        super(DocumentType.PASSPORT);
    }

    @Override
    public boolean isValid() {
        return documentNumber != null && !documentNumber.isEmpty()
                && firstName != null && !firstName.isEmpty()
                && lastName != null && !lastName.isEmpty()
                && dateOfBirth != null && !dateOfBirth.isEmpty()
                && dateOfExpiry != null && !dateOfExpiry.isEmpty();
    }

    @Override
    public String getSummary() {
        return String.format("Passport: %s %s (%s) - %s",
                firstName, lastName, nationality, documentNumber);
    }

    // Inner classes for biometric data
    public static class FingerprintData {
        public byte[] imageData;
        public String fingerPosition;
        public String imageFormat;
        public int width;
        public int height;
    }

    public static class IrisData {
        public byte[] imageData;
        public String eyeLabel;
        public String imageFormat;
        public int width;
        public int height;
    }
}