package com.example.reader.readers.eep;

import android.util.Log;
import java.util.Arrays;
import java.util.List;

/**
 * Parser for Chinese Exit-Entry Permit MRZ (Machine Readable Zone)
 * Handles the 3-line, 30-character format used by 往来港澳通行证 (2014 version)
 */
public class EepMrzParser {

    private static final String TAG = "@@>> EepMrzParser";

    private final MrzCheckDigitValidator checkDigitValidator;
    private final ChineseNameDecoder nameDecoder;

    public EepMrzParser() {
        this.checkDigitValidator = new MrzCheckDigitValidator();
        this.nameDecoder = new ChineseNameDecoder();
    }

    /**
     * Parse result containing all extracted MRZ data
     */
    public static class ParseResult {
        public String documentCode;
        public String cardNumber;
        public String dateOfExpiry;
        public String dateOfBirth;
        public String issuingState;
        public String nationality;
        public String lastName;
        public String firstName;
        public String chineseName;
        public String gender;
        public String endorsementNumber;
        public boolean checksumValid;
        public List<String> validationWarnings;

        @Override
        public String toString() {
            return String.format(
                    "ParseResult{docCode='%s', cardNum='%s', dob='%s', expiry='%s', " +
                            "name='%s %s', chinese='%s', valid=%b}",
                    documentCode, cardNumber, dateOfBirth, dateOfExpiry,
                    lastName, firstName, chineseName, checksumValid
            );
        }
    }

    /**
     * Check if MRZ indicates a Chinese Exit-Entry Permit
     */
    public boolean isChineseExitEntryPermit(String mrz) {
        if (mrz == null || mrz.length() < 2) {
            return false;
        }
        String docCode = mrz.substring(0, 2).toUpperCase();
        Log.d(TAG, "docCode : " + docCode);

        return EepConstants.DOC_CODE_HK_MACAU.equals(docCode)
                || EepConstants.DOC_CODE_TAIWAN.equals(docCode);
    }

    /**
     * Parse the complete MRZ string
     */
    public ParseResult parse(String mrz) {
        ParseResult result = new ParseResult();

        String cleaned = normalizeMrz(mrz);
        if (cleaned.length() < EepConstants.MRZ_TOTAL_LENGTH) {
            Log.w(TAG, "MRZ too short: " + cleaned.length());
            return result;
        }

        String line1 = cleaned.substring(0, 30);
        String line2 = cleaned.substring(30, 60);
        String line3 = cleaned.substring(60, 90);

        Log.d(TAG, "Parsing MRZ lines:");
        Log.d(TAG, "  Line1: " + line1);
        Log.d(TAG, "  Line2: " + line2);
        Log.d(TAG, "  Line3: " + line3);

        parseLine1(line1, result);
        parseLine2(line2, result);
        parseLine3(line3, result);

        return result;
    }

    private String normalizeMrz(String mrz) {
        String cleaned = mrz.replaceAll("\\s+", "");
        while (cleaned.length() < EepConstants.MRZ_TOTAL_LENGTH) {
            cleaned += "<";
        }
        return cleaned;
    }

    private void parseLine1(String line, ParseResult result) {
        // Document code
        result.documentCode = line.substring(
                EepConstants.L1_DOC_CODE_START,
                EepConstants.L1_DOC_CODE_END
        );

        // Card number
        result.cardNumber = line.substring(
                EepConstants.L1_CARD_NUMBER_START,
                EepConstants.L1_CARD_NUMBER_END
        );

        // Validate card number checksum
        char cardCheck = line.charAt(EepConstants.L1_CARD_CHECK_DIGIT);
        if (!checkDigitValidator.verify(result.cardNumber, cardCheck)) {
            Log.w(TAG, "Card number check digit failed");
        }

        // Expiry date
        result.dateOfExpiry = line.substring(
                EepConstants.L1_EXPIRY_START,
                EepConstants.L1_EXPIRY_END
        );

        char expiryCheck = line.charAt(EepConstants.L1_EXPIRY_CHECK_DIGIT);
        if (!checkDigitValidator.verify(result.dateOfExpiry, expiryCheck)) {
            Log.w(TAG, "Expiry date check digit failed");
        }

        // Date of birth
        result.dateOfBirth = line.substring(
                EepConstants.L1_DOB_START,
                EepConstants.L1_DOB_END
        );

        char dobCheck = line.charAt(EepConstants.L1_DOB_CHECK_DIGIT);
        if (!checkDigitValidator.verify(result.dateOfBirth, dobCheck)) {
            Log.w(TAG, "DOB check digit failed");
        }

        // Set nationality (always China for EEP)
        result.issuingState = EepConstants.ISSUING_COUNTRY_CHINA;
        result.nationality = EepConstants.ISSUING_COUNTRY_CHINA;
        result.gender = "UNKNOWN";  // Not in MRZ, comes from DG11
    }

    private void parseLine2(String line, ParseResult result) {
        int separatorPos = line.indexOf("<<");

        if (separatorPos <= 0) {
            Log.w(TAG, "No << separator in Line 2");
            result.firstName = line.replace("<", " ").trim();
            return;
        }

        String beforeSeparator = line.substring(0, separatorPos);
        String afterSeparator = line.substring(separatorPos + 2);

        // Extract encoded Chinese name and pinyin surname
        NameExtractionResult nameResult = extractNames(beforeSeparator);

        result.lastName = nameResult.surname;
        result.firstName = afterSeparator.replace("<", " ").trim();
        result.chineseName = nameDecoder.decode(nameResult.encodedChinese);

        Log.d(TAG, String.format("Parsed names - Surname: '%s', Given: '%s', Chinese: '%s'",
                result.lastName, result.firstName, result.chineseName));
    }

    private void parseLine3(String line, ParseResult result) {
        result.endorsementNumber = line.replace("<", "").trim();
        Log.d(TAG, "Endorsement number: " + result.endorsementNumber);
    }

    private static class NameExtractionResult {
        String encodedChinese;
        String surname;
    }

    private NameExtractionResult extractNames(String beforeSeparator) {
        NameExtractionResult result = new NameExtractionResult();

        if (beforeSeparator.length() > EepConstants.ENCODED_CHINESE_NAME_LENGTH) {
            result.encodedChinese = beforeSeparator.substring(0, EepConstants.ENCODED_CHINESE_NAME_LENGTH);
            result.surname = beforeSeparator.substring(EepConstants.ENCODED_CHINESE_NAME_LENGTH)
                    .replace("<", "").trim();
        } else {
            result.encodedChinese = "";
            result.surname = beforeSeparator.replace("<", "").trim();
        }

        return result;
    }
}