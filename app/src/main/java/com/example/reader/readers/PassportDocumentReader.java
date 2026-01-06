package com.example.reader.readers;

import android.nfc.Tag;
import com.example.reader.PassportReader;
import com.example.reader.models.DocumentData;
import com.example.reader.models.PassportData;

import java.util.ArrayList;
import java.util.List;

/**
 * Passport document reader implementation
 */
public class PassportDocumentReader implements IDocumentReader {

    @Override
    public DocumentData readDocument(Tag tag, DocumentAuthData authData) throws Exception {
        if (!authData.isValid()) {
            throw new IllegalArgumentException("Invalid authentication data");
        }

        // Use existing PassportReader
        PassportReader reader = new PassportReader();
        PassportReader.PassportData oldData = reader.readPassport(
                tag,
                authData.getDocumentNumber(),
                authData.getDateOfBirth(),
                authData.getDateOfExpiry()
        );

        // Convert to new PassportData model
        return convertToPassportData(oldData);
    }

    @Override
    public boolean canRead(Tag tag) {
        // Check if tag supports ISO 14443 Type A or B
        String[] techList = tag.getTechList();
        for (String tech : techList) {
            if (tech.contains("IsoDep")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public DocumentData.DocumentType[] getSupportedTypes() {
        return new DocumentData.DocumentType[] {
                DocumentData.DocumentType.PASSPORT,
                DocumentData.DocumentType.TRAVEL_DOCUMENT
        };
    }

    @Override
    public String getReaderName() {
        return "ICAO ePassport Reader";
    }

    private PassportData convertToPassportData(PassportReader.PassportData oldData) {
        PassportData data = new PassportData();

        // Copy common fields
        data.documentCode = oldData.documentCode;
        data.documentNumber = oldData.documentNumber;
        data.issuingCountry = oldData.issuingState;
        data.issuingAuthority = oldData.issuingAuthority;
        data.dateOfIssue = oldData.dateOfIssue;
        data.dateOfExpiry = oldData.dateOfExpiry;

        data.firstName = oldData.firstName;
        data.lastName = oldData.lastName;
        data.fullName = oldData.fullName;
        data.nationality = oldData.nationality;
        data.dateOfBirth = oldData.dateOfBirth;

        // Fix: placeOfBirth is a List<String> in oldData
        if (oldData.placeOfBirth != null && !oldData.placeOfBirth.isEmpty()) {
            data.placeOfBirth = String.join(", ", oldData.placeOfBirth);
        }

        data.gender = oldData.gender;

        data.faceImages = oldData.faceImages;
        data.faceImageMimeTypes = oldData.faceImageMimeTypes;

        data.hasValidSignature = oldData.hasValidSignature;

        // Fix: authenticationMethod is AuthMethod enum in oldData
        if (oldData.authenticationMethod != null) {
            data.authenticationMethod = oldData.authenticationMethod.toString();
        }

        // Copy passport-specific fields
        data.personalNumber = oldData.personalNumber;

        // Fix: address is List<String> in oldData
        data.address = oldData.address;

        data.telephone = oldData.telephone;
        data.profession = oldData.profession;
        data.optionalData1 = oldData.optionalData1;
        data.optionalData2 = oldData.optionalData2;

        data.hasFingerprintData = oldData.hasFingerprintData;
        data.hasIrisData = oldData.hasIrisData;

        // Convert fingerprints - using separate lists from PassportReader
        if (oldData.fingerprints != null && !oldData.fingerprints.isEmpty()) {
            for (PassportReader.FingerData oldFp : oldData.fingerprints) {
                PassportData.FingerprintData fp = new PassportData.FingerprintData();
                fp.imageData = oldFp.fingerImageData;
                fp.fingerPosition = String.valueOf(oldFp.position);
                fp.imageFormat = oldFp.imageFormat;
                fp.width = oldFp.width;
                fp.height = oldFp.height;
                data.fingerprints.add(fp);
            }
        }

        // Convert iris scans - using the IrisData structure from PassportReader
        if (oldData.irisScans != null && !oldData.irisScans.isEmpty()) {
            for (PassportReader.IrisData oldIris : oldData.irisScans) {
                PassportData.IrisData iris = new PassportData.IrisData();
                iris.imageData = oldIris.irisImageData;
                iris.eyeLabel = oldIris.eyeLabel;
                iris.imageFormat = oldIris.imageFormat;
                // Note: width and height are not available in PassportReader.IrisData
                data.irisScans.add(iris);
            }
        }

        data.hasChipAuthentication = oldData.hasChipAuthentication;
        data.hasActiveAuthentication = oldData.hasActiveAuthentication;
        data.activeAuthenticationPerformed = oldData.activeAuthenticationPerformed;
        data.signingCountry = oldData.signingCountry;
        data.rawSODData = oldData.rawSODData;
        data.dataGroupHashes = oldData.dataGroupHashes;
        data.availableDataGroups = oldData.availableDataGroups;
        data.supportedSecurityProtocols = oldData.supportedSecurityProtocols;
        data.endorsementsAndObservations = oldData.endorsementsAndObservations;

        return data;
    }
}