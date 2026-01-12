package com.example.reader.models;

import android.graphics.Bitmap;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Passport-specific document data
 */
public class PassportData extends DocumentData {

    // Passport-specific fields (DG1)
    public String personalNumber;
    public String optionalData1;
    public String optionalData2;

    // DG5 - Displayed Portrait
    public Bitmap displayedPortrait;

    // DG6 - Reserved for Future Use
    public byte[] dg6Data;

    // DG7 - Displayed Signature
    public Bitmap signatureImage;
    public byte[] signatureImageData;

    // DG8 - Data Features
    public List<DataFeature> dataFeatures = new ArrayList<>();

    // DG9 - Structure Features
    public List<StructureFeature> structureFeatures = new ArrayList<>();

    // DG10 - Substance Features
    public List<SubstanceFeature> substanceFeatures = new ArrayList<>();

    // DG11 - Additional Personal Details (COMPLETE)
    public List<String> otherNames;
    public String dateOfBirth_Full;
    public String title;
    public String personalSummary;
    public byte[] proofOfCitizenship;
    public List<String> otherValidTravelDocNumbers;
    public String custodyInformation;

    // DG12 - Additional Document Details (COMPLETE)
    public List<String> namesOfOtherPersons;
    public String taxOrExitRequirements;
    public byte[] imageOfFront;
    public byte[] imageOfRear;
    public String dateAndTimeOfPersonalization;
    public String personalizationSystemSerialNumber;

    // DG13 - Optional Details
    public byte[] optionalDetailsData;

    // DG14 - Security Options
    public boolean hasTerminalAuthentication;
    public String chipAuthAlgorithm;

    // DG15 - Active Authentication
    public PublicKey activeAuthPublicKey;
    public String activeAuthAlgorithm;

    // DG16 - Emergency Contacts
    public List<EmergencyContact> emergencyContacts = new ArrayList<>();

    // Biometric data (passport-specific)
    public boolean hasFingerprintData;
    public boolean hasIrisData;
    public List<FingerprintData> fingerprints = new ArrayList<>();
    public List<IrisData> irisScans = new ArrayList<>();

    // Security features
    public boolean hasChipAuthentication;
    public boolean hasActiveAuthentication;
    public boolean activeAuthenticationPerformed;
    public boolean chipAuthenticationPerformed;
    public String signingCountry;
    public String documentSignerCertificate;
    public byte[] rawSODData;
    public Map<Integer, byte[]> dataGroupHashes = new HashMap<>();
    public List<Integer> availableDataGroups = new ArrayList<>();
    public List<String> supportedSecurityProtocols = new ArrayList<>();

    // Metadata
    public String passportType;
    public List<String> address;
    public String telephone;
    public String profession;
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

    // Inner classes for complex data types

    public static class FingerprintData {
        public Bitmap fingerImage;
        public byte[] imageData;
        public int position;
        public String fingerPosition;
        public String imageFormat;
        public int width;
        public int height;
    }

    public static class IrisData {
        public Bitmap irisImage;
        public byte[] imageData;
        public String eyeLabel;
        public String imageFormat;
        public int width;
        public int height;
    }

    public static class EmergencyContact {
        public String name;
        public String telephone;
        public String address;
        public String message;
    }

    public static class DataFeature {
        public String featureType;
        public byte[] featureData;
        public String description;
    }

    public static class StructureFeature {
        public String featureType;
        public byte[] featureData;
        public String description;
    }

    public static class SubstanceFeature {
        public String substanceType;
        public byte[] substanceData;
        public String description;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("PassportData{\n");
        sb.append("  documentCode='").append(documentCode).append("'\n");
        sb.append("  documentNumber='").append(documentNumber).append("'\n");
        sb.append("  firstName='").append(firstName).append("'\n");
        sb.append("  lastName='").append(lastName).append("'\n");
        sb.append("  fullName='").append(fullName).append("'\n");
        sb.append("  nationality='").append(nationality).append("'\n");
        sb.append("  issuingCountry='").append(issuingCountry).append("'\n");
        sb.append("  gender='").append(gender).append("'\n");
        sb.append("  dateOfBirth='").append(dateOfBirth).append("'\n");
        sb.append("  dateOfExpiry='").append(dateOfExpiry).append("'\n");
        sb.append("  personalNumber='").append(personalNumber).append("'\n");
        sb.append("  placeOfBirth='").append(placeOfBirth).append("'\n");
        sb.append("  address=").append(address).append("\n");
        sb.append("  telephone='").append(telephone).append("'\n");
        sb.append("  profession='").append(profession).append("'\n");
        sb.append("  issuingAuthority='").append(issuingAuthority).append("'\n");
        sb.append("  dateOfIssue='").append(dateOfIssue).append("'\n");
        sb.append("  endorsementsAndObservations='").append(endorsementsAndObservations).append("'\n");
        sb.append("  authenticationMethod='").append(authenticationMethod).append("'\n");
        sb.append("  hasChipAuthentication=").append(hasChipAuthentication).append("\n");
        sb.append("  hasActiveAuthentication=").append(hasActiveAuthentication).append("\n");
        sb.append("  activeAuthenticationPerformed=").append(activeAuthenticationPerformed).append("\n");
        sb.append("  hasValidSignature=").append(hasValidSignature).append("\n");
        sb.append("  signingCountry='").append(signingCountry).append("'\n");
        sb.append("  hasFingerprintData=").append(hasFingerprintData).append("\n");
        sb.append("  hasIrisData=").append(hasIrisData).append("\n");
        sb.append("  faceImages.size=").append(faceImages != null ? faceImages.size() : 0).append("\n");
        sb.append("  fingerprints.size=").append(fingerprints != null ? fingerprints.size() : 0).append("\n");
        sb.append("  irisScans.size=").append(irisScans != null ? irisScans.size() : 0).append("\n");
        sb.append("  availableDataGroups=").append(availableDataGroups).append("\n");
        sb.append("  supportedSecurityProtocols=").append(supportedSecurityProtocols).append("\n");
        sb.append("}");
        return sb.toString();
    }
}