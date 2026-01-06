package com.example.reader.mrz;

import android.content.Intent;

public interface MrzParser {
    /**
     * Check if this parser can handle the given line
     */
    boolean canParse(String line);

    /**
     * Parse the MRZ line and return the extracted data
     * @return Intent with extracted data, or null if parsing failed
     */
    Intent parse(String line);

    /**
     * Get the document type this parser handles
     */
    String getDocumentType();
}