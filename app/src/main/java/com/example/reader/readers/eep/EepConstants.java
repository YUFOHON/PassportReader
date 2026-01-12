package com.example.reader.readers.eep;

/**
 * Constants for Chinese Exit-Entry Permit (EEP) document reading
 */
public final class EepConstants {

    private EepConstants() {} // Prevent instantiation

    // Document codes
    public static final String DOC_CODE_HK_MACAU = "CS";  // 往来港澳通行证
    public static final String DOC_CODE_TAIWAN = "CD";    // 大陆居民往来台湾通行证

    public static final int DOC_CODE = 1;              // Document code (C)
    public static final int FILLER = 1;                // Filler (<)
    public static final int DOCUMENT_NUMBER = 9;       // Card number
    public static final int CHECK_DIGIT = 1;           // Check digit
    public static final int DATE = 6;                  // YYMMDD format
    public static final int CHINESE_NAME = 12;         // GBK encoded Chinese name
    public static final int ENGLISH_NAME = 18;         // English/Pinyin name
    public static final int GENDER = 1;                // M/F
    public static final int OBSOLETE = 1;              // Obsolete field
    public static final int DOB_CENTURY = 1;           // DOB century indicator
    public static final int THUMBNAIL_MOD = 1;         // Thumbnail modification flag
    public static final int THUMBNAIL_FLAG = 1;        // Thumbnail flag
    public static final int PLACE_OF_BIRTH = 3;        // POB code

    // Combined field lengths for easier navigation
    public static final int DOC_CODE_WITH_FILLER = DOC_CODE + FILLER;                    // 2
    public static final int DOCUMENT_NUMBER_BLOCK = DOCUMENT_NUMBER + CHECK_DIGIT;       // 10
    public static final int EXPIRY_BLOCK = FILLER + DATE + CHECK_DIGIT + FILLER;         // 9
    public static final int DOB_BLOCK = DATE + CHECK_DIGIT + FILLER;                     // 8
    public static final int OVERALL_CHECK = CHECK_DIGIT;                                 // 1
    public static final int OBSOLETE_BLOCK = OBSOLETE + OBSOLETE + DOB_CENTURY +         // 5
            THUMBNAIL_MOD + THUMBNAIL_MOD;
    public static final int THUMBNAIL_BLOCK = THUMBNAIL_FLAG + THUMBNAIL_FLAG;           // 2


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