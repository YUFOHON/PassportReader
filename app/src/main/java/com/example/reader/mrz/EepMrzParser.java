package com.example.reader.mrz;

import android.content.Intent;
import android.util.Log;

import com.example.reader.models.DocumentData;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EepMrzParser extends BaseMrzParser {

    @Override
    public boolean canParse(String line) {
        // EEP MRZ is 30 characters
        if (line.length() < 28 || line.length() > 32) {
            return false;
        }

        // Should start with "CS" (with OCR error tolerance)
        if (!line.startsWith("CS") && !line.startsWith("C5") && !line.startsWith("C$")) {
            return false;
        }

        // Should contain date patterns (6 consecutive digits appear twice: expiry and DOB)
        Pattern datePattern = Pattern.compile("\\d{6}");
        Matcher matcher = datePattern.matcher(line);

        int dateCount = 0;
        while (matcher.find()) {
            dateCount++;
        }

        // Should have at least 2 date patterns (DOB and Expiry)
        return dateCount >= 2;
    }

    @Override
    public Intent parse(String line) {
        try {
            // Clean up common OCR errors for "CS"
            if (line.startsWith("C5")) {
                line = "CS" + line.substring(2);
            } else if (line.startsWith("C$")) {
                line = "CS" + line.substring(2);
            }

            // Ensure line is exactly 30 characters
            if (line.length() < 30) {
                Log.d(TAG, "EEP: Line too short: " + line.length());
                return null;
            }

            // Truncate if slightly longer
            line = line.substring(0, 30);

            // EEP MRZ Format (30 characters total):
            // Positions 1-2:   Document Type Identifier "CS"
            // Positions 3-11:  Document Number (9 chars: 'C' + digit/letter + 7 digits)
            // Position 12:     Check digit for document number
            // Position 13:     Filler '<'
            // Positions 14-19: Expiry Date (YYMMDD)
            // Position 20:     Check digit for expiry date
            // Position 21:     Filler '<'
            // Positions 22-27: Date of Birth (YYMMDD)
            // Position 28:     Check digit for DOB
            // Position 29:     Filler '<'
            // Position 30:     Final composite check digit

            String docType = line.substring(0, 2);  // Should be "CS"
            String docNum = line.substring(2, 11);  // 9 characters
            char docNumCheck = line.charAt(11);
            String expiry = line.substring(13, 19); // 6 characters (YYMMDD)
            char expiryCheck = line.charAt(19);
            String dob = line.substring(21, 27);    // 6 characters (YYMMDD)
            char dobCheck = line.charAt(27);
            char finalCheck = line.charAt(29);

            Log.d(TAG, "EEP: Parsing - DocType: " + docType + ", DocNum: " + docNum + ", DOB: " + dob + ", Expiry: " + expiry);

            // Validate document type
            if (!docType.equals("CS")) {
                Log.d(TAG, "EEP: Invalid document type: " + docType);
                return null;
            }

            // Validate check digits
            if (!validateCheckDigit(docNum, docNumCheck)) {
                Log.d(TAG, "EEP: Invalid document number check digit");
                // Continue - might still be valid with OCR errors
            }

            if (!validateCheckDigit(expiry, expiryCheck)) {
                Log.d(TAG, "EEP: Invalid expiry check digit");
            }

            if (!validateCheckDigit(dob, dobCheck)) {
                Log.d(TAG, "EEP: Invalid DOB check digit");
            }

            // Validate final composite check digit
            String compositeData = line.substring(2, 12) + line.substring(13, 20) + line.substring(21, 28);
            if (!validateCheckDigit(compositeData, finalCheck)) {
                Log.d(TAG, "EEP: Invalid final composite check digit");
            }

            // Clean the extracted data
            docNum = cleanMrzCharacters(docNum);
            dob = cleanMrzCharacters(dob);
            expiry = cleanMrzCharacters(expiry);

            // Validate dates
            if (!isValidDate(dob)) {
                Log.d(TAG, "EEP: Invalid DOB date: " + dob);
                dob = fixDateOcrErrors(dob);
                if (!isValidDate(dob)) {
                    return null;
                }
            }

            if (!isValidDate(expiry)) {
                Log.d(TAG, "EEP: Invalid expiry date: " + expiry);
                expiry = fixDateOcrErrors(expiry);
                if (!isValidDate(expiry)) {
                    return null;
                }
            }

            // Success! Create result intent
            Intent resultIntent = new Intent();
            resultIntent.putExtra("DOC_NUM", docNum);
            resultIntent.putExtra("DOB", dob);
            resultIntent.putExtra("EXPIRY", expiry);
            resultIntent.putExtra("DOC_TYPE", DocumentData.DocumentType.EEP.name());

            Log.d(TAG, "EEP: Successfully parsed MRZ!");
            return resultIntent;

        } catch (Exception e) {
            Log.e(TAG, "EEP: Error parsing MRZ line", e);
            return null;
        }
    }

    @Override
    public String getDocumentType() {
        return "HK/Macao Travel Permit (EEP)";
    }
}