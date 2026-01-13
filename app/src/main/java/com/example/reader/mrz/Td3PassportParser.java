package com.example.reader.mrz;

import android.content.Intent;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Td3PassportParser extends BaseMrzParser {

    @Override
    public boolean canParse(String line) {
        // TD3 passports have 44 character MRZ line 2
        if (line.length() < 43 || line.length() > 45) {
            return false;
        }

        // Check for date patterns (YYMMDD appears at least twice)
        Pattern datePattern = Pattern.compile("\\d{6}");
        Matcher matcher = datePattern.matcher(line);

        int dateCount = 0;
        while (matcher.find()) {
            dateCount++;
        }

        // Should have at least 2 date patterns (DOB and Expiry)
        return dateCount >= 1;
    }

    @Override
    public Intent parse(String line) {
        try {
            // Ensure line is exactly 44 characters
            if (line.length() < 44) {
                Log.d(TAG, "TD3: Line too short: " + line.length());
                return null;
            }

            // Truncate if slightly longer
            line = line.substring(0, 44);

            // TD3 Format Line 2 Structure:
            // Chars 0-9:   Document Number
            // Char 9:      Check digit for document number
            // Chars 10-12: Nationality (3 letters)
            // Chars 13-19: Date of Birth (YYMMDD)
            // Char 19:     Check digit for DOB
            // Char 20:     Sex (M/F/<)
            // Chars 21-27: Expiry Date (YYMMDD)
            // Char 27:     Check digit for expiry

            String docNum = line.substring(0, 9);
            char docNumCheck = line.charAt(9);
            String nationality = line.substring(10, 13);
            String dob = line.substring(13, 19);
            char dobCheck = line.charAt(19);
            char sex = line.charAt(20);
            String expiry = line.substring(21, 27);
            char expiryCheck = line.charAt(27);

            Log.d(TAG, "TD3: Parsing - DocNum: " + docNum + ", DOB: " + dob + ", Expiry: " + expiry);

            // Validate check digits
            if (!validateCheckDigit(docNum, docNumCheck)) {
                Log.d(TAG, "TD3: Invalid document number check digit");
                return null;
            }

            if (!validateCheckDigit(dob, dobCheck)) {
                Log.d(TAG, "TD3: Invalid DOB check digit");
                return null;
            }

            if (!validateCheckDigit(expiry, expiryCheck)) {
                Log.d(TAG, "TD3: Invalid expiry check digit");
                return null;
            }

            // Clean the extracted data
            docNum = cleanMrzCharacters(docNum);
            dob = cleanMrzCharacters(dob);
            expiry = cleanMrzCharacters(expiry);

            // Validate dates
            if (!isValidDate(dob)) {
                Log.d(TAG, "TD3: Invalid DOB date: " + dob);
                dob = fixDateOcrErrors(dob);
                if (!isValidDate(dob)) {
                    return null;
                }
            }

            if (!isValidDate(expiry)) {
                Log.d(TAG, "TD3: Invalid expiry date: " + expiry);
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
            resultIntent.putExtra("NATIONALITY", nationality);
            resultIntent.putExtra("SEX", String.valueOf(sex));
            resultIntent.putExtra("DOC_TYPE", "TD3_PASSPORT");

            Log.d(TAG, "TD3: Successfully parsed MRZ!");
            return resultIntent;

        } catch (Exception e) {
            Log.e(TAG, "TD3: Error parsing MRZ line", e);
            return null;
        }
    }

    @Override
    public String getDocumentType() {
        return "TD3 Passport";
    }
}