package com.example.reader.readers;

/**
 * Authentication data required to read a document
 */
public class DocumentAuthData {

    private String documentNumber;
    private String dateOfBirth;
    private String dateOfExpiry;
    private String pin; // For ID cards
    private String can; // Card Access Number
    private byte[] customKey; // For future use

    public DocumentAuthData(String documentNumber, String dateOfBirth, String dateOfExpiry) {
        this.documentNumber = documentNumber;
        this.dateOfBirth = dateOfBirth;
        this.dateOfExpiry = dateOfExpiry;
    }

    // Getters and setters
    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }

    public String getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public String getDateOfExpiry() { return dateOfExpiry; }
    public void setDateOfExpiry(String dateOfExpiry) { this.dateOfExpiry = dateOfExpiry; }

    public String getPin() { return pin; }
    public void setPin(String pin) { this.pin = pin; }

    public String getCan() { return can; }
    public void setCan(String can) { this.can = can; }

    public byte[] getCustomKey() { return customKey; }
    public void setCustomKey(byte[] customKey) { this.customKey = customKey; }

    public boolean isValid() {
        return documentNumber != null && !documentNumber.isEmpty();
    }
}