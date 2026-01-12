package com.example.reader.utils;

import java.util.List;

/**
 * Utility class for detecting MRZ document types.
 * Supports: TD1, TD2, TD3, EEP, MRVA, MRVB
 */
public class DocumentTypeDetector {
    private static final String TAG = "DocumentTypeDetector";

    /**
     * Detects document type from MRZ lines.
     */
    public static String detect(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Constants.DOC_TYPE_UNKNOWN;
        }

        String firstLine = lines.get(0).toUpperCase().replace(" ", "");
        int lineLength = firstLine.length();
        int lineCount = lines.size();

        // Check for EEP (Exit-Entry Permit) - China
        if (isEEPDocument(firstLine, lineLength)) {
            return Constants.DOC_TYPE_EEP_CHINA;
        }

        // Check for TD3 (Passport)
        if (isTD3Document(firstLine, lineLength, lineCount)) {
            return Constants.DOC_TYPE_TD3;
        }

        // Check for TD1 (ID Card)
        if (isTD1Document(firstLine, lineLength, lineCount)) {
            return Constants.DOC_TYPE_TD1;
        }

        // Check for TD2 (Official Travel Document)
        if (isTD2Document(firstLine, lineLength, lineCount)) {
            return Constants.DOC_TYPE_TD2;
        }

        // Check for MRVA (Visa Type A)
        if (isMRVA(firstLine, lineLength)) {
            return Constants.DOC_TYPE_MRVA;
        }

        // Check for MRVB (Visa Type B)
        if (isMRVB(firstLine, lineLength)) {
            return Constants.DOC_TYPE_MRVB;
        }

        // Fallback detection based on line count and length
        return fallbackDetection(lineCount, lineLength);
    }

    /**
     * Checks if the document is an EEP (Exit-Entry Permit).
     */
    private static boolean isEEPDocument(String firstLine, int lineLength) {
        if (lineLength < 28 || lineLength > 32) {
            return false;
        }

        if (lineLength < 2) {
            return false;
        }

        String prefix = firstLine.substring(0, Math.min(2, firstLine.length()));

        // Check for CS prefix (or common OCR errors)
        return prefix.equals("CS") || prefix.equals("C5") ||
                prefix.equals("C$") || prefix.equals("C8") ||
                (firstLine.charAt(0) == 'C' && firstLine.contains("<"));
    }

    /**
     * Checks if the document is TD3 (Passport).
     * TD3: 2 lines, 44 characters each
     */
    private static boolean isTD3Document(String firstLine, int lineLength, int lineCount) {
        char firstChar = firstLine.length() > 0 ? firstLine.charAt(0) : ' ';

        // Starts with P< or P and length is around 44
        if (firstChar == 'P' && lineLength >= 2 && firstLine.charAt(1) == '<') {
            return true;
        }

        // Line length matches TD3
        if (firstChar == 'P' && lineLength >= 42 && lineLength <= 46) {
            return true;
        }

        // Two lines with appropriate length
        if (lineCount >= 2 && lineLength >= 42 && lineLength <= 46) {
            return true;
        }

        return false;
    }

    /**
     * Checks if the document is TD1 (ID Card).
     * TD1: 3 lines, 30 characters each
     */
    private static boolean isTD1Document(String firstLine, int lineLength, int lineCount) {
        char firstChar = firstLine.length() > 0 ? firstLine.charAt(0) : ' ';

        // ID cards typically start with I, A, or C
        // Length should be 30 characters
        // Should not be EEP (CS prefix)
        if ((firstChar == 'I' || firstChar == 'A' || firstChar == 'C') &&
                lineLength >= 28 && lineLength <= 32) {

            // Make sure it's not EEP
            if (lineLength >= 2) {
                String prefix = firstLine.substring(0, 2);
                if (prefix.equals("CS") || prefix.equals("C5")) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    /**
     * Checks if the document is TD2 (Official Travel Document).
     * TD2: 2 lines, 36 characters each
     */
    private static boolean isTD2Document(String firstLine, int lineLength, int lineCount) {
        // TD2 length is 36 characters
        if (lineLength >= 34 && lineLength <= 38) {
            char firstChar = firstLine.length() > 0 ? firstLine.charAt(0) : ' ';

            // Exclude visas
            if (firstChar != 'V') {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the document is MRVA (Visa Type A).
     * MRVA: 2 lines, 44 characters each
     */
    private static boolean isMRVA(String firstLine, int lineLength) {
        char firstChar = firstLine.length() > 0 ? firstLine.charAt(0) : ' ';

        // Starts with V and length is 44 (same as passport)
        return firstChar == 'V' && lineLength >= 42 && lineLength <= 46;
    }

    /**
     * Checks if the document is MRVB (Visa Type B).
     * MRVB: 2 lines, 36 characters each
     */
    private static boolean isMRVB(String firstLine, int lineLength) {
        char firstChar = firstLine.length() > 0 ? firstLine.charAt(0) : ' ';

        // Starts with V and length is 36 (same as TD2)
        return firstChar == 'V' && lineLength >= 34 && lineLength <= 38;
    }

    /**
     * Fallback detection based on line count and length.
     */
    private static String fallbackDetection(int lineCount, int lineLength) {
        // Based on line length
        if (lineLength >= 42 && lineLength <= 46) {
            return Constants.DOC_TYPE_TD3; // Passport-sized
        }

        if (lineLength >= 34 && lineLength <= 38) {
            return Constants.DOC_TYPE_TD2; // TD2-sized
        }

        if (lineLength >= 28 && lineLength <= 32) {
            // Could be TD1 or EEP
            return lineCount == 1 ? Constants.DOC_TYPE_EEP_CHINA : Constants.DOC_TYPE_TD1;
        }

        return Constants.DOC_TYPE_UNKNOWN;
    }

    /**
     * Gets the expected line length for a document type.
     */
    public static int getLineLength(String docType) {
        switch (docType) {
            case Constants.DOC_TYPE_TD1:
            case Constants.DOC_TYPE_EEP_CHINA:
                return 30;

            case Constants.DOC_TYPE_TD2:
            case Constants.DOC_TYPE_MRVB:
                return 36;

            case Constants.DOC_TYPE_TD3:
            case Constants.DOC_TYPE_MRVA:
                return 44;

            default:
                return 44; // Default to passport length
        }
    }

    /**
     * Gets the required number of lines for a document type.
     */
    public static int getRequiredLineCount(String docType) {
        switch (docType) {
            case Constants.DOC_TYPE_TD1:
                return 3;

            case Constants.DOC_TYPE_TD2:
            case Constants.DOC_TYPE_TD3:
            case Constants.DOC_TYPE_MRVA:
            case Constants.DOC_TYPE_MRVB:
                return 2;

            case Constants.DOC_TYPE_EEP_CHINA:
                return 1;

            default:
                return 2; // Default to 2 lines
        }
    }

    /**
     * Gets a human-readable name for the document type.
     */
    public static String getDocumentTypeName(String docType) {
        switch (docType) {
            case Constants.DOC_TYPE_TD1:
                return "ID Card (TD1)";

            case Constants.DOC_TYPE_TD2:
                return "Travel Document (TD2)";

            case Constants.DOC_TYPE_TD3:
                return "Passport (TD3)";

            case Constants.DOC_TYPE_EEP_CHINA:
                return "往來港澳通行證 (EEP)";

            case Constants.DOC_TYPE_MRVA:
                return "Visa Type A";

            case Constants.DOC_TYPE_MRVB:
                return "Visa Type B";

            default:
                return "Unknown Document";
        }
    }

    /**
     * Checks if a document type is valid.
     */
    public static boolean isValidDocumentType(String docType) {
        return docType != null && !Constants.DOC_TYPE_UNKNOWN.equals(docType);
    }

    /**
     * Gets the document category (passport, id, visa).
     */
    public static String getDocumentCategory(String docType) {
        switch (docType) {
            case Constants.DOC_TYPE_TD3:
                return "PASSPORT";

            case Constants.DOC_TYPE_TD1:
            case Constants.DOC_TYPE_EEP_CHINA:
                return "ID_CARD";

            case Constants.DOC_TYPE_MRVA:
            case Constants.DOC_TYPE_MRVB:
                return "VISA";

            case Constants.DOC_TYPE_TD2:
                return "TRAVEL_DOCUMENT";

            default:
                return "UNKNOWN";
        }
    }
}