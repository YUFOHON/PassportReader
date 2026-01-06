package com.example.reader.readers.eep;

import android.util.Log;

/**
 * Validates MRZ check digits according to ICAO Doc 9303 standard
 */
public class MrzCheckDigitValidator {

    private static final String TAG = "@@>> MrzCheckDigit";

    /**
     * Verify a check digit against the given data
     *
     * @param data The data string to verify
     * @param checkDigit The expected check digit character ('0'-'9')
     * @return true if valid, false otherwise
     */
    public boolean verify(String data, char checkDigit) {
        if (data == null || data.isEmpty()) {
            return false;
        }

        int expected = calculate(data);
        int actual = parseCheckDigit(checkDigit);

        if (actual < 0) {
            Log.w(TAG, "Invalid check digit character: " + checkDigit);
            return false;
        }

        boolean valid = (expected == actual);
        if (!valid) {
            Log.d(TAG, "Check digit mismatch: expected"+
                    expected +" "+ actual +" "+ data);
        }

        return valid;
    }

    /**
     * Calculate the check digit for given data
     */
    public int calculate(String data) {
        int sum = 0;
        int[] weights = EepConstants.CHECK_DIGIT_WEIGHTS;

        for (int i = 0; i < data.length(); i++) {
            int value = getCharacterValue(data.charAt(i));
            sum += value * weights[i % weights.length];
        }

        return sum % 10;
    }

    private int getCharacterValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else if (c >= 'A' && c <= 'Z') {
            return c - 'A' + 10;
        } else if (c == '<') {
            return 0;
        } else {
            Log.w(TAG, "Unknown MRZ character: " + c);
            return 0;
        }
    }

    private int parseCheckDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        return -1;  // Invalid
    }
}