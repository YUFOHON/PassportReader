package com.example.reader.utils;

import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for cleaning and normalizing MRZ text.
 * Handles OCR corrections, character validation, and line normalization.
 */
public class MRZCleaner {
    private static final String TAG = "MRZCleaner";

    // OCR common misrecognition corrections
    private static final Map<Character, Character> OCR_CORRECTIONS = new HashMap<>();
    static {
        OCR_CORRECTIONS.put('|', 'I');
        OCR_CORRECTIONS.put('!', 'I');
        OCR_CORRECTIONS.put('l', 'I');
        OCR_CORRECTIONS.put('}', 'J');
        OCR_CORRECTIONS.put('{', 'C');
        OCR_CORRECTIONS.put('$', 'S');
        OCR_CORRECTIONS.put('@', '0');
        OCR_CORRECTIONS.put('°', '0');
        OCR_CORRECTIONS.put('(', 'C');
        OCR_CORRECTIONS.put(')', '0');
        OCR_CORRECTIONS.put('o', 'O');
        OCR_CORRECTIONS.put('Q', 'O');  // Common in dates
        OCR_CORRECTIONS.put('D', '0');  // Common in numbers
    }

    /**
     * Cleans a single MRZ line by removing invalid characters and applying corrections.
     */
    public static String cleanMRZLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        char[] chars = text.toUpperCase().toCharArray();
        StringBuilder result = new StringBuilder(chars.length);

        for (char c : chars) {
            // Skip whitespace and tabs
            if (c == ' ' || c == '\t') {
                continue;
            }

            // Apply OCR corrections
            Character corrected = OCR_CORRECTIONS.get(c);
            if (corrected != null) {
                c = corrected;
            }

            // Only keep valid MRZ characters
            if (MRZUtils.isValidMRZChar(c)) {
                result.append(c);
            }
        }

        String cleaned = result.toString();
        return correctOAndZero(cleaned);
    }

    /**
     * Cleans an EEP (Exit-Entry Permit) line with specific corrections.
     */
    public static String cleanEEPLine(String text) {
        String result = cleanMRZLine(text);

        // Correct common EEP prefix errors
        if (result.length() >= 2) {
            String prefix = result.substring(0, 2);
            if (prefix.equals("C5") || prefix.equals("C8") ||
                    prefix.equals("C$") || prefix.equals("CO")) {
                result = "CS" + result.substring(2);
            }
        }

        return result;
    }

    /**
     * Corrects O and 0 based on context.
     * In MRZ, context determines whether a character should be O or 0.
     */
    private static String correctOAndZero(String text) {
        if (text.length() < 2) {
            return text;
        }

        char[] chars = text.toCharArray();

        // Special handling for EEP documents (CS prefix)
        if (chars[0] == 'C' && (chars[1] == 'S' || chars[1] == '5' || chars[1] == '8')) {
            // After CS prefix, everything should be digits (0) not letters (O)
            for (int i = 2; i < chars.length; i++) {
                if (chars[i] == 'O') {
                    chars[i] = '0';
                }
            }
            return new String(chars);
        }

        // Context-based O/0 correction
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == 'O' || chars[i] == '0') {
                boolean prevIsDigit = i > 0 && Character.isDigit(chars[i - 1]);
                boolean nextIsDigit = i < chars.length - 1 && Character.isDigit(chars[i + 1]);

                // If surrounded by digits, it's likely a zero
                if (prevIsDigit || nextIsDigit) {
                    chars[i] = '0';
                } else {
                    chars[i] = 'O';
                }
            }
        }

        return new String(chars);
    }

    public static String extractAndClean(List<String> mrzLines, String documentType) {
        if (mrzLines == null || mrzLines.isEmpty()) {
            Log.d(TAG, "extractAndClean: null or empty input");
            return null;
        }

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG, "extractAndClean: " + mrzLines.size() + " candidates, docType=" + documentType);
        for (int i = 0; i < mrzLines.size(); i++) {
            Log.d(TAG, "  Raw line " + i + ": [" + mrzLines.get(i) + "] len=" + mrzLines.get(i).length());
        }

        String docType = documentType != null ? documentType :
                DocumentTypeDetector.detect(mrzLines);

        // Handle single-line EEP documents
        if (Constants.DOC_TYPE_EEP_CHINA.equals(docType) || mrzLines.size() == 1) {
            String cleanedLine = cleanEEPLine(mrzLines.get(0));
            String normalized = normalizeMRZLineLength(cleanedLine, 30);

            if (cleanedLine.length() >= 28 && cleanedLine.charAt(0) == 'C') {
                return normalized;
            }
            return null;
        }

        // Handle multi-line documents (TD1, TD2, TD3)
        int targetLength = DocumentTypeDetector.getLineLength(docType);
        int requiredCount = DocumentTypeDetector.getRequiredLineCount(docType);
        int minAcceptableLength = (int) (targetLength * 0.85);

        Log.d(TAG, "Target: " + targetLength + " chars, required: " + requiredCount + " lines, minLen: " + minAcceptableLength);

        StringBuilder result = new StringBuilder();
        int validCount = 0;

        for (String line : mrzLines) {
            if (validCount >= requiredCount) break;

            String cleaned = cleanMRZLine(line);
            Log.d(TAG, "  Cleaned: [" + cleaned + "] len=" + cleaned.length());

            // Check length
            if (cleaned.length() < minAcceptableLength) {
                Log.d(TAG, "  ❌ REJECTED: too short (" + cleaned.length() + " < " + minAcceptableLength + ")");
                continue;
            }

            // Check TD3 first line prefix - BE MORE LENIENT
            if (validCount == 0 && "TD3".equals(docType)) {
                char firstChar = cleaned.charAt(0);
                // Accept P, V (visa), or common OCR errors for P
                if (firstChar != 'P' && firstChar != 'V') {
                    Log.d(TAG, "  ❌ REJECTED: TD3 first line doesn't start with P/V: '" + firstChar + "'");
                    // BUT don't reject if it looks like MRZ (has << pattern)
                    if (!cleaned.contains("<<")) {
                        continue;
                    }
                    Log.d(TAG, "  ✅ RECOVERED: contains << pattern, accepting anyway");
                }
            }

            String normalized = normalizeMRZLineLength(cleaned, targetLength);
            Log.d(TAG, "  ✅ ACCEPTED: " + normalized.substring(0, Math.min(20, normalized.length())) + "...");

            if (validCount > 0) {
                result.append("\n");
            }
            result.append(normalized);
            validCount++;
        }

        Log.d(TAG, "Valid lines found: " + validCount + "/" + requiredCount);

        if (validCount < 2) {
            Log.d(TAG, "❌ FAILED: Not enough valid lines");
            return null;
        }

        String finalResult = result.toString();
        Log.d(TAG, "✅ SUCCESS: Extracted MRZ:\n" + finalResult);
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        return finalResult;
    }
    /**
     * Normalizes MRZ line length by padding with '<' or truncating.
     */
    public static String normalizeMRZLineLength(String line, int targetLength) {
        if (line == null) {
            return "<".repeat(targetLength);
        }

        int len = line.length();

        if (len < targetLength) {
            // Pad with filler characters
            return line + "<".repeat(targetLength - len);
        } else if (len > targetLength) {
            // Truncate to target length
            return line.substring(0, targetLength);
        }

        return line;
    }

    /**
     * Checks if two MRZ texts are similar enough to be considered the same.
     * Uses fuzzy matching to account for minor OCR variations.
     */
    public static boolean areSimilar(String mrz1, String mrz2) {
        if (mrz1 == null || mrz2 == null) {
            return false;
        }

        String[] lines1 = mrz1.split("\n");
        String[] lines2 = mrz2.split("\n");

        if (lines1.length != lines2.length) {
            return false;
        }

        for (int i = 0; i < lines1.length; i++) {
            if (!areLinesSimila(lines1[i], lines2[i])) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if two MRZ lines are similar.
     */
    private static boolean areLinesSimila(String line1, String line2) {
        int minLen = Math.min(line1.length(), line2.length());
        int maxLen = Math.max(line1.length(), line2.length());

        if (maxLen == 0) {
            return true;
        }

        // Length difference threshold
        if (maxLen - minLen > 6) {
            return false;
        }

        // Character matching
        int matches = 0;
        for (int j = 0; j < minLen; j++) {
            if (line1.charAt(j) == line2.charAt(j)) {
                matches++;
            }
        }

        // Calculate similarity ratio
        float similarity = (float) matches / maxLen;

        // Longer lines need higher similarity (more tolerant of errors)
        float threshold = maxLen > 40 ? 0.75f : 0.80f;

        return similarity >= threshold;
    }

    /**
     * Validates if a string looks like valid MRZ text.
     */
    public static boolean isValidMRZ(String mrzText) {
        if (mrzText == null || mrzText.isEmpty()) {
            return false;
        }

        String[] lines = mrzText.split("\n");

        for (String line : lines) {
            if (!isValidMRZLine(line)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Validates a single MRZ line.
     */
    private static boolean isValidMRZLine(String line) {
        if (line == null || line.length() < 20) {
            return false;
        }

        // Check if at least 80% of characters are valid MRZ characters
        int validCount = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 128 && MRZUtils.isValidMRZChar(c)) {
                validCount++;
            }
        }

        float validRatio = (float) validCount / line.length();
        return validRatio >= 0.8f;
    }

    /**
     * Removes filler characters (<) from MRZ text for display purposes.
     */
    public static String removeFiller(String mrzText) {
        if (mrzText == null) {
            return "";
        }
        return mrzText.replace("<", " ").trim();
    }

    /**
     * Splits MRZ text into individual fields for parsing.
     */
    public static String[] splitMRZFields(String mrzLine) {
        if (mrzLine == null || mrzLine.isEmpty()) {
            return new String[0];
        }

        // Split by one or more filler characters
        return mrzLine.split("<+");
    }

    /**
     * Calculates the check digit for MRZ validation.
     * Used for validating document numbers, dates, etc.
     */
    public static int calculateCheckDigit(String data) {
        if (data == null || data.isEmpty()) {
            return -1;
        }

        int[] weights = {7, 3, 1};
        int sum = 0;

        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            int value;

            if (c == '<') {
                value = 0;
            } else if (Character.isDigit(c)) {
                value = c - '0';
            } else if (Character.isLetter(c)) {
                value = c - 'A' + 10;
            } else {
                return -1; // Invalid character
            }

            sum += value * weights[i % 3];
        }

        return sum % 10;
    }

    /**
     * Validates a check digit in MRZ data.
     */
    public static boolean validateCheckDigit(String data, char checkDigit) {
        if (!Character.isDigit(checkDigit)) {
            return false;
        }

        int calculated = calculateCheckDigit(data);
        int expected = checkDigit - '0';

        return calculated == expected;
    }

    /**
     * Cleans and formats a date from MRZ format (YYMMDD).
     */
    public static String formatMRZDate(String mrzDate) {
        if (mrzDate == null || mrzDate.length() != 6) {
            return mrzDate;
        }

        try {
            String year = mrzDate.substring(0, 2);
            String month = mrzDate.substring(2, 4);
            String day = mrzDate.substring(4, 6);

            // Convert YY to YYYY (assume 19xx for years > 50, 20xx otherwise)
            int yy = Integer.parseInt(year);
            int fullYear = yy > 50 ? 1900 + yy : 2000 + yy;

            return String.format("%04d-%s-%s", fullYear, month, day);
        } catch (Exception e) {
            Log.e(TAG, "Error formatting MRZ date: " + mrzDate, e);
            return mrzDate;
        }
    }

    /**
     * Extracts name components from MRZ name field.
     * MRZ format: SURNAME<<GIVEN<NAMES
     */
    public static NameComponents extractNameComponents(String mrzName) {
        if (mrzName == null || mrzName.isEmpty()) {
            return new NameComponents("", "");
        }

        String[] parts = mrzName.split("<<");

        String surname = parts.length > 0 ? removeFiller(parts[0]) : "";
        String givenNames = parts.length > 1 ? removeFiller(parts[1]) : "";

        return new NameComponents(surname, givenNames);
    }

    /**
     * Name components extracted from MRZ.
     */
    public static class NameComponents {
        public final String surname;
        public final String givenNames;

        public NameComponents(String surname, String givenNames) {
            this.surname = surname;
            this.givenNames = givenNames;
        }

        public String getFullName() {
            if (surname.isEmpty() && givenNames.isEmpty()) {
                return "";
            }
            if (surname.isEmpty()) {
                return givenNames;
            }
            if (givenNames.isEmpty()) {
                return surname;
            }
            return givenNames + " " + surname;
        }
    }

    /**
     * Repairs common OCR errors in specific MRZ positions.
     */
    public static String repairKnownErrors(String mrzLine, int lineNumber, String docType) {
        if (mrzLine == null || mrzLine.isEmpty()) {
            return mrzLine;
        }

        char[] chars = mrzLine.toCharArray();

        // TD3 (Passport) specific repairs
        if (Constants.DOC_TYPE_TD3.equals(docType)) {
            if (lineNumber == 1 && chars.length >= 2) {
                // First line should start with P<
                if (chars[0] != 'P') chars[0] = 'P';
                if (chars[1] != '<') chars[1] = '<';
            }
        }

        // EEP specific repairs
        if (Constants.DOC_TYPE_EEP_CHINA.equals(docType)) {
            if (chars.length >= 2) {
                // Should start with CS
                if (chars[0] != 'C') chars[0] = 'C';
                if (chars[1] == '5' || chars[1] == '8' || chars[1] == 'O') {
                    chars[1] = 'S';
                }
            }
        }

        return new String(chars);
    }

    /**
     * Calculates confidence score for cleaned MRZ text.
     */
    public static float calculateConfidence(String originalMRZ, String cleanedMRZ) {
        if (originalMRZ == null || cleanedMRZ == null) {
            return 0f;
        }

        if (originalMRZ.isEmpty() || cleanedMRZ.isEmpty()) {
            return 0f;
        }

        // Compare character by character
        int matches = 0;
        int total = Math.min(originalMRZ.length(), cleanedMRZ.length());

        for (int i = 0; i < total; i++) {
            if (originalMRZ.charAt(i) == cleanedMRZ.charAt(i)) {
                matches++;
            }
        }

        return (float) matches / total;
    }
}