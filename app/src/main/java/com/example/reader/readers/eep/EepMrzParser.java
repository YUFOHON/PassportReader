package com.example.reader.readers.eep;

import android.util.Log;

/**
 * Parser for Chinese Exit-Entry Permit MRZ (Machine Readable Zone)
 * Handles the 3-line, 30-character format used by 往来港澳通行证 (2014 version)
 *
 * This parser follows field-length based extraction instead of position-based,
 * matching the iOS implementation logic.
 */
public class EepMrzParser {

    private static final String TAG = "EepMrzParser";

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
        public String placeOfBirth;
        public boolean isValid;
        public boolean checksumValid;

        @Override
        public String toString() {
            return String.format(
                    "ParseResult{docCode='%s', cardNum='%s', dob='%s', expiry='%s', " +
                            "name='%s %s', chinese='%s', pob='%s', valid=%b}",
                    documentCode, cardNumber, dateOfBirth, dateOfExpiry,
                    lastName, firstName, chineseName, placeOfBirth, isValid
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

        String cleaned = mrz.replaceAll("[\\s\\n\\r]", "");
        if (cleaned.length() < 2) {
            return false;
        }

        String docCode = cleaned.substring(0, 2).toUpperCase();
        Log.d(TAG, "Document code: " + docCode);

        return "CS".equals(docCode) || "CD".equals(docCode);
    }

    /**
     * Parse the complete MRZ string using field-length based extraction
     * Following iOS logic exactly
     */
    public ParseResult parse(String mrz) {
        ParseResult result = new ParseResult();

        String normalized = normalizeMrz(mrz);
        if (normalized.length() < 90) {
            Log.w(TAG, "MRZ too short: " + normalized.length());
            result.isValid = false;
            return result;
        }

        Log.d(TAG, "Parsing normalized MRZ (90 chars):");
        Log.d(TAG, "  Full: " + normalized);

        // Parse using field-length based extraction (iOS style)
        parseFieldBased(normalized, result);

        return result;
    }

    /**
     * Field-length based parsing matching iOS implementation
     */
    private void parseFieldBased(String mrz, ParseResult result) {
        int idx = 0;

        // Skip: doc code (1) + filler (1)
        String docCode = substring(mrz, idx, EepConstants.DOC_CODE).trim();
        result.documentCode = docCode.isEmpty() ? "C" : docCode;
        idx += EepConstants.DOC_CODE_WITH_FILLER;

        // Extract: document number (9)
        result.cardNumber = substring(mrz, idx, EepConstants.DOCUMENT_NUMBER).trim();
        Log.d(TAG, "Card number: " + result.cardNumber);

        // Verify check digit
        char cardCheckDigit = mrz.charAt(idx + EepConstants.DOCUMENT_NUMBER);
        if (!checkDigitValidator.verify(result.cardNumber, cardCheckDigit)) {
            Log.w(TAG, "Card number check digit failed");
            result.checksumValid = false;
        }
        idx += EepConstants.DOCUMENT_NUMBER_BLOCK;

        // Extract: expiry date (skip filler, read 6 digits)
        String expiry = substring(mrz, idx + EepConstants.FILLER, EepConstants.DATE).trim();
        result.dateOfExpiry = expiry;
        Log.d(TAG, "Expiry date: " + result.dateOfExpiry);

        char expiryCheckDigit = mrz.charAt(idx + EepConstants.FILLER + EepConstants.DATE);
        if (!checkDigitValidator.verify(expiry, expiryCheckDigit)) {
            Log.w(TAG, "Expiry date check digit failed");
            result.checksumValid = false;
        }
        idx += EepConstants.EXPIRY_BLOCK;

        // Extract: date of birth (6) with century calculation
        String dob = substring(mrz, idx, EepConstants.DATE).trim();
        result.dateOfBirth = calculateDOB(dob);
        Log.d(TAG, "Date of birth: " + result.dateOfBirth);

        char dobCheckDigit = mrz.charAt(idx + EepConstants.DATE);
        if (!checkDigitValidator.verify(dob, dobCheckDigit)) {
            Log.w(TAG, "DOB check digit failed");
            result.checksumValid = false;
        }
        idx += EepConstants.DOB_BLOCK;

        // Skip: overall check digit
        idx += EepConstants.OVERALL_CHECK;

        // Extract: Chinese name (12 chars, GBK encoded)
        String chineseEncoded = substring(mrz, idx, EepConstants.CHINESE_NAME);
        result.chineseName = nameDecoder.decode(chineseEncoded);
        Log.d(TAG, "Chinese name: " + result.chineseName);
        idx += EepConstants.CHINESE_NAME;

        // Extract: English name (18 chars)
        String englishName = substring(mrz, idx, EepConstants.ENGLISH_NAME);
        parseEnglishName(englishName, result);
        Log.d(TAG, "English name: " + result.firstName + " " + result.lastName);
        idx += EepConstants.ENGLISH_NAME;

        // Extract: Gender (1)
        result.gender = substring(mrz, idx, EepConstants.GENDER).trim();
        if (result.gender.isEmpty()) {
            result.gender = "UNKNOWN";
        }
        Log.d(TAG, "Gender: " + result.gender);
        idx += EepConstants.GENDER;

        // Skip: obsolete fields (5)
        idx += EepConstants.OBSOLETE_BLOCK;

        // Skip: thumbnail flags (2)
        idx += EepConstants.THUMBNAIL_BLOCK;

        // Extract: Place of birth (3)
        String pob = substring(mrz, idx, EepConstants.PLACE_OF_BIRTH)
                .trim()
                .replace("<", " ")
                .trim();
        result.placeOfBirth = pob.isEmpty() ? null : pob;
        Log.d(TAG, "Place of birth: " + result.placeOfBirth);

        // Set fixed values
        result.issuingState = EepConstants.ISSUING_COUNTRY_CHINA;
        result.nationality = EepConstants.ISSUING_COUNTRY_CHINA;
        result.isValid = true;
    }

    /**
     * Calculate full date of birth from YYMMDD format
     * Matches iOS calculateDOB logic
     */
    private String calculateDOB(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6) {
            return yymmdd;
        }

        try {
            java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
            String today = formatter.format(new java.util.Date());
            String century = today.substring(0, 2);

            String fullDate = century + yymmdd;

            // If birth date is in future, subtract 1 from century
            if (fullDate.compareTo(today) > 0) {
                int cent = Integer.parseInt(century);
                fullDate = String.valueOf(cent - 1) + yymmdd;
            }

            return fullDate;
        } catch (Exception e) {
            Log.w(TAG, "Error calculating DOB: " + e.getMessage());
            return yymmdd;
        }
    }

    /**
     * Parse English/Pinyin name field
     * Matches iOS parseEnglishName logic
     */
    private void parseEnglishName(String field, ParseResult result) {
        String cleaned = field.replaceAll("^<+|<+$", "");
        String[] parts = cleaned.split("<+");

        // Filter empty parts
        java.util.List<String> nonEmpty = new java.util.ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                nonEmpty.add(part);
            }
        }

        if (nonEmpty.size() >= 2) {
            result.lastName = nonEmpty.get(0);
            result.firstName = nonEmpty.get(1);
        } else if (nonEmpty.size() == 1) {
            result.lastName = nonEmpty.get(0);
            result.firstName = null;
        } else {
            result.lastName = null;
            result.firstName = null;
        }
    }

    /**
     * Normalize MRZ to exactly 90 characters
     */
    private String normalizeMrz(String mrz) {
        String cleaned = mrz.replaceAll("[\\s\\n\\r]", "");
        while (cleaned.length() < 90) {
            cleaned += "<";
        }
        return cleaned;
    }

    /**
     * Extract substring safely
     */
    private String substring(String str, int start, int length) {
        if (start < 0 || start + length > str.length()) {
            return "";
        }
        return str.substring(start, start + length);
    }
}