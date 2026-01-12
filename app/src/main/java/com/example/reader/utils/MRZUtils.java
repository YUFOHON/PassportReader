package com.example.reader.utils;

public class MRZUtils {
    private static final boolean[] VALID_MRZ_CHARS = new boolean[128];

    static {
        for (char c = 'A'; c <= 'Z'; c++) VALID_MRZ_CHARS[c] = true;
        for (char c = '0'; c <= '9'; c++) VALID_MRZ_CHARS[c] = true;
        VALID_MRZ_CHARS['<'] = true;
    }

    public static boolean isQuickEEPCheck(String text) {
        if (text.length() < 28 || text.length() > 32) return false;
        char first = text.length() > 0 ? text.charAt(0) : ' ';
        return first == 'C' && text.contains("<");
    }

    public static boolean isQuickMRZCheck(String text) {
        int len = text.length();
        if (len < 20 || len > 50) return false;
        if (text.startsWith("P<")) return true;
        if (!text.contains("<")) return false;

        int validCount = 0;
        int checkCount = 0;
        for (int i = 0; i < len; i += 3) {
            char c = text.charAt(i);
            if (c < 128 && VALID_MRZ_CHARS[c]) validCount++;
            checkCount++;
        }

        return checkCount == 0 || (float) validCount / checkCount >= 0.5f;
    }

    public static boolean isValidMRZChar(char c) {
        return c < 128 && VALID_MRZ_CHARS[c];
    }
}