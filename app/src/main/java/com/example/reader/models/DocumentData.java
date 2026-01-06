package com.example.reader.models;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for all document data
 */
public abstract class DocumentData {

    public enum DocumentType {
        PASSPORT,
        ID_CARD,
        DRIVERS_LICENSE,
        VISA,
        RESIDENCE_PERMIT,
        TRAVEL_DOCUMENT,
        EEEP, UNKNOWN
    }

    // Common fields for all documents
    public DocumentType documentType;
    public String documentCode;
    public String documentNumber;
    public String issuingCountry;
    public String issuingAuthority;
    public String dateOfIssue;
    public String dateOfExpiry;

    // Personal Information
    public String firstName;
    public String lastName;
    public String fullName;
    public String nationality;
    public String dateOfBirth;
    public String placeOfBirth;
    public String gender;

    // Biometric Data
    public List<Bitmap> faceImages = new ArrayList<>();
    public List<String> faceImageMimeTypes = new ArrayList<>();

    // Security & Validation
    public boolean hasValidSignature;
    public String authenticationMethod;
    public List<String> securityFeatures = new ArrayList<>();

    // Additional data storage for extensibility
    public Map<String, Object> additionalData = new HashMap<>();

    // Raw data for advanced processing
    public byte[] rawData;

    public DocumentData(DocumentType type) {
        this.documentType = type;
    }

    /**
     * Validate if document has minimum required data
     */
    public abstract boolean isValid();

    /**
     * Get a human-readable summary
     */
    public abstract String getSummary();
}