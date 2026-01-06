package com.example.reader.readers.eep;

/**
 * Constants for Chinese Exit-Entry Permit (EEP) document reading
 */
public final class EepConstants {

    private EepConstants() {} // Prevent instantiation

    // Document codes
    public static final String DOC_CODE_HK_MACAU = "CS";  // 往来港澳通行证
    public static final String DOC_CODE_TAIWAN = "CD";    // 大陆居民往来台湾通行证

    // MRZ structure
    public static final int MRZ_LINE_LENGTH = 30;
    public static final int MRZ_TOTAL_LENGTH = 90;  // 3 lines × 30 chars
    public static final int ENCODED_CHINESE_NAME_LENGTH = 12;

    // MRZ Line 1 positions (0-indexed)
    public static final int L1_DOC_CODE_START = 0;
    public static final int L1_DOC_CODE_END = 2;
    public static final int L1_CARD_NUMBER_START = 2;
    public static final int L1_CARD_NUMBER_END = 11;
    public static final int L1_CARD_CHECK_DIGIT = 11;
    public static final int L1_EXPIRY_START = 13;
    public static final int L1_EXPIRY_END = 19;
    public static final int L1_EXPIRY_CHECK_DIGIT = 19;
    public static final int L1_DOB_START = 21;
    public static final int L1_DOB_END = 27;
    public static final int L1_DOB_CHECK_DIGIT = 27;
    public static final int L1_OVERALL_CHECK_DIGIT = 29;

    // Check digit weights (ICAO Doc 9303)
    public static final int[] CHECK_DIGIT_WEIGHTS = {7, 3, 1};

    // Issuing country
    public static final String ISSUING_COUNTRY_CHINA = "CHN";

    // APDU
    public static final byte[] EMRTD_AID = {
            (byte) 0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01
    };

    // Timeouts
    public static final int ISO_DEP_TIMEOUT_MS = 20000;
}