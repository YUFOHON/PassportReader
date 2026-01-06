package com.example.reader.readers.eep;

import android.util.Log;
import java.io.UnsupportedEncodingException;

/**
 * Decodes GBK-encoded Chinese names from MRZ format
 *
 * The encoding scheme converts each GBK byte to 2 MRZ characters:
 * - Hex 0-9 → A-J
 * - Hex A-F → K-P
 *
 * Example: 证 (GBK: 0xD6A4) → NGKE
 *   D(13) → N, 6 → G, A(10) → K, 4 → E
 */
public class ChineseNameDecoder {

    private static final String TAG = "@@>> ChineseNameDecoder";

    private static final String CHARSET_GBK = "GBK";
    private static final String CHARSET_GB2312 = "GB2312";

    /**
     * Decode MRZ-encoded Chinese name to actual Chinese characters
     *
     * @param encoded The MRZ-encoded string (e.g., "NGKELMP<NBPJ")
     * @return Decoded Chinese string (e.g., "证件样本"), or original if decode fails
     */
    public String decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }

        String cleaned = encoded.replace("<", "");
        if (cleaned.isEmpty()) {
            return "";
        }

        // Ensure length is multiple of 4 (2 bytes = 4 MRZ chars per Chinese char)
        int validLength = (cleaned.length() / 4) * 4;
        if (validLength == 0) {
            return "";
        }
        if (validLength != cleaned.length()) {
            Log.w(TAG, String.format("Truncating encoded name from %d to %d chars",
                    cleaned.length(), validLength));
            cleaned = cleaned.substring(0, validLength);
        }

        byte[] gbkBytes = convertToGbkBytes(cleaned);
        return decodeGbkBytes(gbkBytes, encoded);
    }

    private byte[] convertToGbkBytes(String encoded) {
        StringBuilder hexString = new StringBuilder();

        for (char c : encoded.toCharArray()) {
            int hexValue = mrzCharToHex(c);
            hexString.append(Integer.toHexString(hexValue));
        }

        String hex = hexString.toString();
        byte[] bytes = new byte[hex.length() / 2];

        for (int i = 0; i < bytes.length; i++) {
            String byteHex = hex.substring(i * 2, i * 2 + 2);
            bytes[i] = (byte) Integer.parseInt(byteHex, 16);
        }

        return bytes;
    }

    private int mrzCharToHex(char c) {
        if (c >= 'A' && c <= 'J') {
            return c - 'A';      // A=0, B=1, ..., J=9
        } else if (c >= 'K' && c <= 'P') {
            return c - 'K' + 10; // K=10, L=11, ..., P=15
        } else {
            Log.w(TAG, "Unknown encoded character: " + c);
            return 0;
        }
    }

    private String decodeGbkBytes(byte[] bytes, String fallback) {
        // Try GBK first
        try {
            return new String(bytes, CHARSET_GBK);
        } catch (UnsupportedEncodingException e) {
            Log.w(TAG, "GBK not supported, trying GB2312");
        }

        // Fallback to GB2312
        try {
            return new String(bytes, CHARSET_GB2312);
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Neither GBK nor GB2312 supported");
        }

        return fallback;
    }

    /**
     * Check if a string contains Chinese characters
     */
    public static boolean containsChinese(String s) {
        if (s == null) return false;

        for (char c : s.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}