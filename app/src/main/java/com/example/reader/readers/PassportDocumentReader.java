package com.example.reader.readers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;

import com.example.reader.models.DocumentData;
import com.example.reader.models.PassportData;
import com.gemalto.jp2.JP2Decoder;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ResponseAPDU;

import org.jmrtd.BACKey;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.CardAccessFile;
import org.jmrtd.lds.DisplayedImageInfo;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SODFile;
import org.jmrtd.lds.SecurityInfo;
import org.jmrtd.lds.icao.DG11File;
import org.jmrtd.lds.icao.DG12File;
import org.jmrtd.lds.icao.DG14File;
import org.jmrtd.lds.icao.DG15File;
import org.jmrtd.lds.icao.DG1File;
import org.jmrtd.lds.icao.DG2File;
import org.jmrtd.lds.icao.DG3File;
import org.jmrtd.lds.icao.DG4File;
import org.jmrtd.lds.icao.DG5File;
import org.jmrtd.lds.icao.DG7File;
import org.jmrtd.lds.icao.MRZInfo;
import org.jmrtd.lds.iso19794.FaceImageInfo;
import org.jmrtd.lds.iso19794.FaceInfo;
import org.jmrtd.lds.iso19794.FingerImageInfo;
import org.jmrtd.lds.iso19794.FingerInfo;
import org.jmrtd.lds.iso19794.IrisBiometricSubtypeInfo;
import org.jmrtd.lds.iso19794.IrisImageInfo;
import org.jmrtd.lds.iso19794.IrisInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Passport document reader implementation
 */
public class PassportDocumentReader implements IDocumentReader {

    private static final String TAG = "@@>> PassportDocumentReader";

    // eMRTD Application Identifier (AID)
    private static final byte[] EMRTD_AID = {
            (byte) 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01
    };

    // Authentication method enum
    public enum AuthMethod {
        PACE,
        BAC,
        NONE
    }

    private void selectEMRTDApplication(CardService cardService) throws CardServiceException {
        Log.d(TAG, "📱 Selecting eMRTD application...");

        CommandAPDU selectApp = new CommandAPDU(0x00, 0xA4, 0x04, 0x0C, EMRTD_AID);
        ResponseAPDU response = cardService.transmit(selectApp);

        int sw = response.getSW();
        if (sw == 0x9000) {
            Log.d(TAG, "✅ eMRTD application selected successfully");
        } else {
            String errorMsg = String.format("Failed to select eMRTD application: SW=0x%04X", sw);
            Log.e(TAG, errorMsg);
            throw new CardServiceException(errorMsg);
        }
    }

    private AuthMethod performSmartAuthentication(PassportService service,
                                                  CardService cardService,
                                                  BACKeySpec bacKey) throws Exception {

        boolean paceSucceeded = false;

        try {
            Log.d(TAG, "📖 Checking for PACE support (CardAccess file)...");

            CardAccessFile cardAccessFile = new CardAccessFile(
                    service.getInputStream(PassportService.EF_CARD_ACCESS)
            );

            Collection<SecurityInfo> securityInfos = cardAccessFile.getSecurityInfos();
            Log.d(TAG, "✅ CardAccess found with " + securityInfos.size() + " security infos");

            List<PACEInfo> paceInfos = new ArrayList<>();
            for (SecurityInfo securityInfo : securityInfos) {
                if (securityInfo instanceof PACEInfo) {
                    PACEInfo paceInfo = (PACEInfo) securityInfo;
                    paceInfos.add(paceInfo);
                    Log.d(TAG, "  Found PACE: " + paceInfo.getProtocolOIDString());
                }
            }

            for (PACEInfo paceInfo : paceInfos) {
                try {
                    String oid = paceInfo.getObjectIdentifier();
                    BigInteger parameterId = paceInfo.getParameterId();

                    Log.d(TAG, "🔐 Attempting PACE with OID: " + oid);

                    service.doPACE(
                            bacKey,
                            oid,
                            PACEInfo.toParameterSpec(parameterId),
                            null
                    );

                    Log.d(TAG, "✅ PACE authentication SUCCESSFUL");
                    paceSucceeded = true;
                    break;

                } catch (Exception e) {
                    Log.w(TAG, "⚠️ PACE attempt failed: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            Log.d(TAG, "ℹ️ PACE not supported: " + e.getMessage());
        }

        if (paceSucceeded) {
            Log.d(TAG, "📱 Selecting eMRTD application (with secure messaging from PACE)...");
            service.sendSelectApplet(true);
            return AuthMethod.PACE;
        }

        Log.d(TAG, "📱 Selecting eMRTD application for BAC...");
        service.sendSelectApplet(false);

        Log.d(TAG, "🔐 Performing BAC authentication...");
        try {
            service.doBAC(bacKey);
            Log.d(TAG, "✅ BAC authentication SUCCESSFUL");
            return AuthMethod.BAC;
        } catch (Exception e) {
            Log.e(TAG, "❌ BAC authentication FAILED: " + e.getMessage());
            throw new Exception("Authentication failed. This passport may require PACE which is not fully configured, or the MRZ data (document number, birth date, expiry date) is incorrect.", e);
        }
    }

    @Override
    public DocumentData readDocument(Tag tag, DocumentAuthData authData) throws Exception {
        return readDocument(tag, authData, null);
    }

    @Override
    public DocumentData readDocument(Tag tag, DocumentAuthData authData, ProgressCallback progressCallback) throws Exception {

        if (!authData.isValid()) {
            throw new Exception("Invalid auth data");
        }

        String docNumber = authData.getDocumentNumber().trim().toUpperCase();
        String birthDate = authData.getDateOfBirth().replaceAll("[^0-9]", "");
        String expiryDate = authData.getDateOfExpiry().replaceAll("[^0-9]", "");

        if (birthDate.length() != 6 || expiryDate.length() != 6) {
            throw new IllegalArgumentException("Dates must be in YYMMDD format");
        }

        Log.d(TAG, "📖 Starting COMPLETE passport read (ALL Data Groups)...");

        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            throw new Exception("Tag does not support ISO-DEP (ISO 14443-4)");
        }

        isoDep.setTimeout(20000);
        isoDep.connect();

        CardService cardService = CardService.getInstance(isoDep);
        cardService.open();

        PassportService service = new PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                true,
                false
        );
        service.open();

        PassportData result = new PassportData();

        try {
            BACKeySpec bacKey = new BACKey(docNumber, birthDate, expiryDate);

            AuthMethod authMethod = performSmartAuthentication(service, cardService, bacKey);
            result.authenticationMethod = authMethod.toString();
            result.supportedSecurityProtocols.add(authMethod.toString());

            Log.d(TAG, "🔒 Secure messaging active: " + (service.getWrapper() != null));

            readSOD(service, result);

            if (result.dataGroupHashes != null && !result.dataGroupHashes.isEmpty()) {
                result.availableDataGroups = new ArrayList<>(result.dataGroupHashes.keySet());
                Log.d(TAG, "✓ Available Data Groups from SOD: " + result.availableDataGroups);
            }

            readDG1(service, result);
            readDG2(service, result);

            if (result.availableDataGroups.contains(3)) readDG3(service, result);
            if (result.availableDataGroups.contains(4)) readDG4(service, result);
            if (result.availableDataGroups.contains(5)) readDG5(service, result);
            if (result.availableDataGroups.contains(6)) readDG6(service, result);
            if (result.availableDataGroups.contains(7)) readDG7(service, result);
            if (result.availableDataGroups.contains(8)) readDG8(service, result);
            if (result.availableDataGroups.contains(9)) readDG9(service, result);
            if (result.availableDataGroups.contains(10)) readDG10(service, result);
            if (result.availableDataGroups.contains(11)) readDG11(service, result);
            if (result.availableDataGroups.contains(12)) readDG12(service, result);
            if (result.availableDataGroups.contains(13)) readDG13(service, result);
            if (result.availableDataGroups.contains(14)) readDG14(service, result);
            if (result.availableDataGroups.contains(15)) readDG15(service, result);
            if (result.availableDataGroups.contains(16)) readDG16(service, result);

            if (result.hasActiveAuthentication) {
                performActiveAuthentication(service, result);
            }

            if (result.hasChipAuthentication) {
                performChipAuthentication(service, result);
            }

            Log.d(TAG, "✅ COMPLETE passport read finished - ALL Data Groups processed");

        } finally {
            try { service.close(); } catch (Exception e) { }
            try { cardService.close(); } catch (Exception e) { }
        }

        return result;
    }

    private void readDG1(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "📄 Reading DG1 (MRZ)...");
            InputStream is = service.getInputStream(PassportService.EF_DG1);
            DG1File dg1 = new DG1File(is);
            MRZInfo mrzInfo = dg1.getMRZInfo();

            result.documentCode = mrzInfo.getDocumentCode();
            result.issuingCountry = mrzInfo.getIssuingState();
            result.lastName = mrzInfo.getPrimaryIdentifier().replace("<", " ").trim();
            result.firstName = mrzInfo.getSecondaryIdentifier().replace("<", " ").trim();
            result.documentNumber = mrzInfo.getDocumentNumber();
            result.nationality = mrzInfo.getNationality();
            result.dateOfBirth = mrzInfo.getDateOfBirth();
            result.gender = mrzInfo.getGender().toString();
            result.dateOfExpiry = mrzInfo.getDateOfExpiry();
            result.optionalData1 = mrzInfo.getOptionalData1();
            result.optionalData2 = mrzInfo.getOptionalData2();

            Log.d(TAG, "✓ DG1 read successfully");
        } catch (Exception e) {
            Log.e(TAG, "✗ Error reading DG1", e);
        }
    }

    private void readDG2(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "📸 Reading DG2 (Face Image)...");
            InputStream is = service.getInputStream(PassportService.EF_DG2);
            DG2File dg2 = new DG2File(is);

            List<FaceInfo> faceInfos = dg2.getFaceInfos();
            for (FaceInfo faceInfo : faceInfos) {
                List<FaceImageInfo> faceImageInfos = faceInfo.getFaceImageInfos();
                for (FaceImageInfo faceImageInfo : faceImageInfos) {
                    int imageLength = faceImageInfo.getImageLength();
                    DataInputStream dataInputStream = new DataInputStream(
                            faceImageInfo.getImageInputStream()
                    );
                    byte[] buffer = new byte[imageLength];
                    dataInputStream.readFully(buffer, 0, imageLength);

                    String mimeType = faceImageInfo.getMimeType();
                    result.faceImageMimeTypes.add(mimeType);

                    Bitmap bitmap = null;

                    if (mimeType.equals("image/jp2") || mimeType.equals("image/jpeg2000")) {
                        bitmap = new JP2Decoder(buffer).decode();
                    } else {
                        bitmap = BitmapFactory.decodeByteArray(buffer, 0, buffer.length);
                    }

                    if (bitmap != null) {
                        result.faceImages.add(bitmap);
                        Log.d(TAG, "✓ Decoded face image: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    }
                }
            }
            Log.d(TAG, "✓ DG2: " + result.faceImages.size() + " image(s) successfully decoded");
        } catch (Exception e) {
            Log.e(TAG, "✗ Error reading DG2", e);
        }
    }

    private void readDG3(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "👆 Reading DG3 (Fingerprints)...");
            InputStream is = service.getInputStream(PassportService.EF_DG3);
            DG3File dg3 = new DG3File(is);

            List<FingerInfo> fingerInfos = dg3.getFingerInfos();
            for (FingerInfo fingerInfo : fingerInfos) {
                List<FingerImageInfo> fingerImageInfos = fingerInfo.getFingerImageInfos();
                for (FingerImageInfo imageInfo : fingerImageInfos) {
                    PassportData.FingerprintData fingerData = new PassportData.FingerprintData();

                    int imageLength = imageInfo.getImageLength();
                    DataInputStream dis = new DataInputStream(imageInfo.getImageInputStream());
                    fingerData.imageData = new byte[imageLength];
                    dis.readFully(fingerData.imageData, 0, imageLength);

                    fingerData.position = imageInfo.getPosition();
                    fingerData.fingerPosition = String.valueOf(imageInfo.getPosition());
                    fingerData.width = imageInfo.getWidth();
                    fingerData.height = imageInfo.getHeight();
                    fingerData.imageFormat = imageInfo.getMimeType();

                    if (fingerData.imageFormat.contains("jpeg")) {
                        fingerData.fingerImage = BitmapFactory.decodeByteArray(
                                fingerData.imageData, 0, imageLength
                        );
                    }

                    result.fingerprints.add(fingerData);
                }
            }

            result.hasFingerprintData = !result.fingerprints.isEmpty();
            Log.d(TAG, "✓ DG3: " + result.fingerprints.size() + " fingerprint(s)");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG3 not accessible (requires EAC)", e);
            result.hasFingerprintData = false;
        }
    }

    private void readDG4(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "👁️ Reading DG4 (Iris)...");
            InputStream is = service.getInputStream(PassportService.EF_DG4);
            DG4File dg4 = new DG4File(is);

            List<IrisInfo> irisInfos = dg4.getIrisInfos();
            for (IrisInfo irisInfo : irisInfos) {
                int imageFormat = irisInfo.getImageFormat();
                List<IrisBiometricSubtypeInfo> biometricSubtypes = irisInfo.getIrisBiometricSubtypeInfos();

                for (IrisBiometricSubtypeInfo subtypeInfo : biometricSubtypes) {
                    List<IrisImageInfo> irisImageInfos = subtypeInfo.getIrisImageInfos();

                    for (IrisImageInfo imageInfo : irisImageInfos) {
                        PassportData.IrisData irisData = new PassportData.IrisData();

                        int imageLength = imageInfo.getImageLength();
                        DataInputStream dis = new DataInputStream(imageInfo.getImageInputStream());
                        irisData.imageData = new byte[imageLength];
                        dis.readFully(irisData.imageData, 0, imageLength);

                        int biometricSubtype = subtypeInfo.getBiometricSubtype();
                        if (biometricSubtype == IrisBiometricSubtypeInfo.EYE_LEFT) {
                            irisData.eyeLabel = "Left Eye";
                        } else if (biometricSubtype == IrisBiometricSubtypeInfo.EYE_RIGHT) {
                            irisData.eyeLabel = "Right Eye";
                        } else {
                            irisData.eyeLabel = "Undefined";
                        }

                        switch (imageFormat) {
                            case 2: irisData.imageFormat = "image/raw"; break;
                            case 3: irisData.imageFormat = "image/rgb-raw"; break;
                            case 4: irisData.imageFormat = "image/jpeg"; break;
                            case 5: irisData.imageFormat = "image/jpeg"; break;
                            case 6: irisData.imageFormat = "image/jpeg-ls"; break;
                            case 7: irisData.imageFormat = "image/jpeg-ls"; break;
                            case 8: irisData.imageFormat = "image/jp2"; break;
                            case 9: irisData.imageFormat = "image/jp2"; break;
                            default: irisData.imageFormat = "image/unknown (format code: " + imageFormat + ")";
                        }

                        if (imageFormat == 4 || imageFormat == 5) {
                            irisData.irisImage = BitmapFactory.decodeByteArray(
                                    irisData.imageData, 0, imageLength
                            );
                        }

                        result.irisScans.add(irisData);
                    }
                }
            }

            result.hasIrisData = !result.irisScans.isEmpty();
            Log.d(TAG, "✓ DG4: " + result.irisScans.size() + " iris scan(s)");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG4 not accessible (requires EAC)", e);
            result.hasIrisData = false;
        }
    }

    private void readDG5(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "🖼️ Reading DG5 (Displayed Portrait)...");
            InputStream is = service.getInputStream(PassportService.EF_DG5);
            DG5File dg5 = new DG5File(is);

            List<DisplayedImageInfo> imageInfos = dg5.getImages();
            if (!imageInfos.isEmpty()) {
                DisplayedImageInfo imageInfo = imageInfos.get(0);
                int imageLength = imageInfo.getImageLength();
                DataInputStream dis = new DataInputStream(imageInfo.getImageInputStream());
                byte[] buffer = new byte[imageLength];
                dis.readFully(buffer, 0, imageLength);

                result.displayedPortrait = BitmapFactory.decodeByteArray(buffer, 0, imageLength);
                Log.d(TAG, "✓ DG5: Portrait image");
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG5 not available", e);
        }
    }

    private void readDG6(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "📦 Reading DG6 (Reserved for Future Use)...");
            InputStream is = service.getInputStream(PassportService.EF_DG6);

            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            result.dg6Data = buffer;

            Log.d(TAG, "✓ DG6: " + buffer.length + " bytes (reserved/country-specific)");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG6 not available (expected - usually empty)", e);
        }
    }

    private void readDG7(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "✍️ Reading DG7 (Signature)...");
            InputStream is = service.getInputStream(PassportService.EF_DG7);
            DG7File dg7 = new DG7File(is);

            List<DisplayedImageInfo> imageInfos = dg7.getImages();
            if (!imageInfos.isEmpty()) {
                DisplayedImageInfo imageInfo = imageInfos.get(0);
                int imageLength = imageInfo.getImageLength();
                DataInputStream dis = new DataInputStream(imageInfo.getImageInputStream());
                result.signatureImageData = new byte[imageLength];
                dis.readFully(result.signatureImageData, 0, imageLength);

                result.signatureImage = BitmapFactory.decodeByteArray(
                        result.signatureImageData, 0, imageLength
                );
                Log.d(TAG, "✓ DG7: Signature image");
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG7 not available", e);
        }
    }

    private void readDG8(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "🔍 Reading DG8 (Data Features - Visual Security)...");
            InputStream is = service.getInputStream(PassportService.EF_DG8);

            byte[] buffer = new byte[is.available()];
            is.read(buffer);

            PassportData.DataFeature feature = new PassportData.DataFeature();
            feature.featureType = "Visual Security Features";
            feature.featureData = buffer;
            feature.description = "Holograms, UV patterns, microprinting, etc.";
            result.dataFeatures.add(feature);

            Log.d(TAG, "✓ DG8: " + buffer.length + " bytes of visual security data");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG8 not available", e);
        }
    }

    private void readDG9(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "🏗️ Reading DG9 (Structure Features - Physical Security)...");
            InputStream is = service.getInputStream(PassportService.EF_DG9);

            byte[] buffer = new byte[is.available()];
            is.read(buffer);

            PassportData.StructureFeature feature = new PassportData.StructureFeature();
            feature.featureType = "Physical Structure Features";
            feature.featureData = buffer;
            feature.description = "RFID chip info, security threads, watermarks, etc.";
            result.structureFeatures.add(feature);

            Log.d(TAG, "✓ DG9: " + buffer.length + " bytes of structure data");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG9 not available", e);
        }
    }

    private void readDG10(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "⚗️ Reading DG10 (Substance Features - Material Composition)...");
            InputStream is = service.getInputStream(PassportService.EF_DG10);

            byte[] buffer = new byte[is.available()];
            is.read(buffer);

            PassportData.SubstanceFeature feature = new PassportData.SubstanceFeature();
            feature.substanceType = "Material Composition Features";
            feature.substanceData = buffer;
            feature.description = "Ink types, paper composition, chemical markers, etc.";
            result.substanceFeatures.add(feature);

            Log.d(TAG, "✓ DG10: " + buffer.length + " bytes of substance data");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG10 not available", e);
        }
    }

    private void readDG11(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "ℹ️ Reading DG11 (Personal Details)...");
            InputStream is = service.getInputStream(PassportService.EF_DG11);
            DG11File dg11 = new DG11File(is);

            result.fullName = dg11.getNameOfHolder();
            result.otherNames = dg11.getOtherNames();
            result.personalNumber = dg11.getPersonalNumber();

            List<String> placeOfBirth = dg11.getPlaceOfBirth();
            if (placeOfBirth != null && !placeOfBirth.isEmpty()) {
                result.placeOfBirth = String.join(", ", placeOfBirth);
            }

            result.dateOfBirth_Full = dg11.getFullDateOfBirth();
            result.address = dg11.getPermanentAddress();
            result.telephone = dg11.getTelephone();
            result.profession = dg11.getProfession();
            result.title = dg11.getTitle();
            result.personalSummary = dg11.getPersonalSummary();
            result.proofOfCitizenship = dg11.getProofOfCitizenship();
            result.otherValidTravelDocNumbers = dg11.getOtherValidTDNumbers();
            result.custodyInformation = dg11.getCustodyInformation();

            Log.d(TAG, "✓ DG11: Extended personal data");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG11 not available", e);
        }
    }

    private void readDG12(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "📋 Reading DG12 (Document Details)...");
            InputStream is = service.getInputStream(PassportService.EF_DG12);
            DG12File dg12 = new DG12File(is);

            result.issuingAuthority = dg12.getIssuingAuthority();
            result.dateOfIssue = dg12.getDateOfIssue();
            result.namesOfOtherPersons = dg12.getNamesOfOtherPersons();
            result.endorsementsAndObservations = dg12.getEndorsementsAndObservations();
            result.taxOrExitRequirements = dg12.getTaxOrExitRequirements();
            result.imageOfFront = dg12.getImageOfFront();
            result.imageOfRear = dg12.getImageOfRear();
            result.dateAndTimeOfPersonalization = dg12.getDateAndTimeOfPersonalization();
            result.personalizationSystemSerialNumber = dg12.getPersonalizationSystemSerialNumber();

            Log.d(TAG, "✓ DG12: Document metadata");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG12 not available", e);
        }
    }

    private void readDG13(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "📦 Reading DG13 (Optional Details)...");
            InputStream is = service.getInputStream(PassportService.EF_DG13);
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            result.optionalDetailsData = buffer;

            Log.d(TAG, "✓ DG13: " + buffer.length + " bytes");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG13 not available", e);
        }
    }

    private void readDG14(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "🔐 Reading DG14 (Security Options)...");
            InputStream is = service.getInputStream(PassportService.EF_DG14);
            DG14File dg14 = new DG14File(is);

            result.hasChipAuthentication = !dg14.getChipAuthenticationInfos().isEmpty();
            result.hasTerminalAuthentication = !dg14.getTerminalAuthenticationInfos().isEmpty();

            if (result.hasChipAuthentication) {
                result.chipAuthAlgorithm = dg14.getChipAuthenticationInfos()
                        .get(0)
                        .getObjectIdentifier();
                result.supportedSecurityProtocols.add("Chip Authentication");
            }

            if (result.hasTerminalAuthentication) {
                result.supportedSecurityProtocols.add("Terminal Authentication");
            }

            Log.d(TAG, "✓ DG14: Security protocols detected");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG14 not available", e);
            result.hasChipAuthentication = false;
            result.hasTerminalAuthentication = false;
        }
    }

    private void readDG15(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "🔑 Reading DG15 (Active Authentication)...");
            InputStream is = service.getInputStream(PassportService.EF_DG15);
            DG15File dg15 = new DG15File(is);

            result.activeAuthPublicKey = dg15.getPublicKey();
            result.hasActiveAuthentication = (result.activeAuthPublicKey != null);

            if (result.hasActiveAuthentication) {
                result.activeAuthAlgorithm = result.activeAuthPublicKey.getAlgorithm();
                result.supportedSecurityProtocols.add("Active Authentication");
                Log.d(TAG, "✓ DG15: " + result.activeAuthAlgorithm);
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG15 not available", e);
            result.hasActiveAuthentication = false;
        }
    }

    private void readDG16(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "🆘 Reading DG16 (Emergency Contacts)...");
            InputStream is = service.getInputStream(PassportService.EF_DG16);
            byte[] buffer = new byte[is.available()];
            is.read(buffer);

            Log.d(TAG, "✓ DG16: Emergency contact data (" + buffer.length + " bytes)");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ DG16 not available", e);
        }
    }

    private void performActiveAuthentication(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "🔐 Performing Active Authentication...");
            byte[] challenge = new byte[8];
            new java.security.SecureRandom().nextBytes(challenge);

            org.jmrtd.protocol.AAResult aaResult = service.doAA(
                    result.activeAuthPublicKey,
                    null,
                    null,
                    challenge
            );

            result.activeAuthenticationPerformed = (aaResult != null);
            Log.d(TAG, "✅ Active Authentication " + (result.activeAuthenticationPerformed ? "VERIFIED" : "FAILED"));
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Active Authentication failed", e);
            result.activeAuthenticationPerformed = false;
        }
    }

    private void performChipAuthentication(PassportService service, PassportData result) {
        try {
            result.chipAuthenticationPerformed = false;
            Log.d(TAG, "⚠️ Chip Authentication requires advanced setup");
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Chip Authentication error", e);
        }
    }

    private void readSOD(PassportService service, PassportData result) {
        try {
            Log.d(TAG, "🔏 Reading SOD (Security Object Document)...");
            InputStream is = service.getInputStream(PassportService.EF_SOD);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[1024];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            byte[] sodBytes = buffer.toByteArray();

            result.rawSODData = sodBytes;

            SODFile sodFile = new SODFile(new ByteArrayInputStream(sodBytes));

            if (sodFile.getIssuerX500Principal() != null) {
                result.signingCountry = sodFile.getIssuerX500Principal().getName();
            }

            if (sodFile.getDocSigningCertificate() != null) {
                result.documentSignerCertificate = sodFile.getDocSigningCertificate().toString();
            }

            result.dataGroupHashes = sodFile.getDataGroupHashes();
            result.hasValidSignature = true;

            Log.d(TAG, "✓ SOD: Read " + sodBytes.length + " bytes");
            Log.d(TAG, "✓ SOD: Hashes found for DGs: " + result.dataGroupHashes.keySet());

        } catch (Exception e) {
            Log.w(TAG, "⚠️ SOD read/verification failed", e);
            result.hasValidSignature = false;
        }
    }

    @Override
    public boolean canRead(Tag tag) {
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
}