package com.example.reader.mrz;

import android.util.Log;

public abstract class BaseMrzParser implements MrzParser {
    protected static final String TAG = "MrzParser";

    protected String cleanMrzCharacters(String text) {
        return text
                .replace("O", "0")  // Letter O to zero
                .replace("Q", "0")
                .replace("D", "0")
                .replace("I", "1")  // Letter I to one
                .replace("l", "1")  // Lowercase L to one
                .replace("Z", "2")
                .replace("S", "5")
                .replace("B", "8")
                .replace("<", "")   // Remove filler characters
                .toUpperCase();
    }

    protected boolean isValidDate(String date) {
        if (date.length() != 6) return false;

        try {
            int year = Integer.parseInt(date.substring(0, 2));
            int month = Integer.parseInt(date.substring(2, 4));
            int day = Integer.parseInt(date.substring(4, 6));

            // Basic date validation
            if (month < 1 || month > 12) return false;
            if (day < 1 || day > 31) return false;

            // More strict validation for months with fewer days
            if ((month == 4 || month == 6 || month == 9 || month == 11) && day > 30) {
                return false;
            }
            if (month == 2 && day > 29) {
                return false;
            }

            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    protected boolean validateCheckDigit(String data, char checkDigit) {
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
                return false; // Invalid character
            }

            sum += value * weights[i % 3];
        }

        int calculatedCheck = sum % 10;

        // Handle check digit
        int providedCheck;
        if (checkDigit == '<') {
            providedCheck = 0;
        } else if (Character.isDigit(checkDigit)) {
            providedCheck = checkDigit - '0';
        } else {
            return false;
        }

        return calculatedCheck == providedCheck;
    }

    protected String fixDateOcrErrors(String date) {
        if (date.length() != 6) return date;

        StringBuilder fixed = new StringBuilder(date);

        // Fix common OCR errors in dates
        for (int i = 0; i < fixed.length(); i++) {
            char c = fixed.charAt(i);

            // Common OCR misreads for digits
            if (c == 'O' || c == 'Q' || c == 'D') {
                fixed.setCharAt(i, '0');
            } else if (c == 'I' || c == 'l') {
                fixed.setCharAt(i, '1');
            } else if (c == 'Z') {
                fixed.setCharAt(i, '2');
            } else if (c == 'S') {
                fixed.setCharAt(i, '5');
            } else if (c == 'B') {
                fixed.setCharAt(i, '8');
            }
        }

        return fixed.toString();
    }
}