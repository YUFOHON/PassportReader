package com.example.reader.mrz;

import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class MrzParserManager {
    private static final String TAG = "MrzParserManager";

    private final List<MrzParser> parsers = new ArrayList<>();

    public MrzParserManager() {
        // Add parsers in priority order
        parsers.add(new EepMrzParser());
        parsers.add(new Td3PassportParser());
        // Add more parsers as you create them (TD1, TD2, etc.)
    }

    /**
     * Try to parse MRZ text with all available parsers
     * @param mrzText Full MRZ text (may be multiple lines)
     * @param docType Detected document type hint
     * @return Intent with parsed data, or null if parsing failed
     */
    public Intent parseMrz(String mrzText, String docType) {
        if (mrzText == null || mrzText.isEmpty()) {
            return null;
        }

        String[] lines = mrzText.split("\n");

        // Try each line with each parser
        for (String line : lines) {
            String cleanLine = line.trim().toUpperCase();
            if (cleanLine.isEmpty()) continue;

            for (MrzParser parser : parsers) {
                if (parser.canParse(cleanLine)) {
                    Log.d(TAG, "Trying parser: " + parser.getDocumentType() + " for line: " + cleanLine);
                    Intent result = parser.parse(cleanLine);
                    if (result != null) {
                        Log.d(TAG, "Successfully parsed with: " + parser.getDocumentType());
                        return result;
                    }
                }
            }
        }

        // If no parser worked, try with the full MRZ for multi-line documents
        for (MrzParser parser : parsers) {
            String fullMrz = mrzText.replace("\n", "");
            if (parser.canParse(fullMrz)) {
                Log.d(TAG, "Trying parser with full MRZ: " + parser.getDocumentType());
                Intent result = parser.parse(fullMrz);
                if (result != null) {
                    Log.d(TAG, "Successfully parsed full MRZ with: " + parser.getDocumentType());
                    return result;
                }
            }
        }

        Log.w(TAG, "No parser could handle the MRZ text");
        return null;
    }
}