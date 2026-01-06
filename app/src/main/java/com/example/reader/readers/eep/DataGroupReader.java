package com.example.reader.readers.eep;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.gemalto.jp2.JP2Decoder;
import org.jmrtd.PassportService;
import org.jmrtd.lds.icao.*;
import org.jmrtd.lds.iso19794.*;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles reading of individual Data Groups from eMRTD chip
 */
public class DataGroupReader {

    private static final String TAG = "@@>> DataGroupReader";

    private final PassportService service;

    public DataGroupReader(PassportService service) {
        this.service = service;
    }

    /**
     * Read DG1 (MRZ Information)
     */
    public DG1File readDG1() throws Exception {
        InputStream is = service.getInputStream(PassportService.EF_DG1);
        return new DG1File(is);
    }

    /**
     * Read DG2 (Facial Biometrics) and extract face images
     */
    public List<FaceImageResult> readDG2() throws Exception {
        List<FaceImageResult> results = new ArrayList<>();

        InputStream is = service.getInputStream(PassportService.EF_DG2);
        DG2File dg2 = new DG2File(is);

        for (FaceInfo faceInfo : nullSafe(dg2.getFaceInfos())) {
            for (FaceImageInfo imageInfo : nullSafe(faceInfo.getFaceImageInfos())) {
                FaceImageResult result = extractFaceImage(imageInfo);
                if (result != null) {
                    results.add(result);
                }
            }
        }

        Log.d(TAG, "DG2: extracted " + results.size() + " face images");
        return results;
    }

    public static class FaceImageResult {
        public final Bitmap bitmap;
        public final String mimeType;

        public FaceImageResult(Bitmap bitmap, String mimeType) {
            this.bitmap = bitmap;
            this.mimeType = mimeType;
        }
    }

    private FaceImageResult extractFaceImage(FaceImageInfo imageInfo) {
        try {
            int length = imageInfo.getImageLength();
            DataInputStream dis = new DataInputStream(imageInfo.getImageInputStream());
            byte[] imageBytes = new byte[length];
            dis.readFully(imageBytes);

            String mimeType = imageInfo.getMimeType();
            Bitmap bitmap = decodeBitmap(imageBytes, mimeType);

            return bitmap != null ? new FaceImageResult(bitmap, mimeType) : null;

        } catch (Exception e) {
            Log.e(TAG, "Failed to extract face image: " + e.getMessage());
            return null;
        }
    }

    private Bitmap decodeBitmap(byte[] data, String mimeType) {
        if (isJpeg2000(mimeType)) {
            return new JP2Decoder(data).decode();
        }
        return BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    private boolean isJpeg2000(String mimeType) {
        return "image/jp2".equalsIgnoreCase(mimeType)
                || "image/jpeg2000".equalsIgnoreCase(mimeType);
    }

    /**
     * Read DG11 (Additional Personal Details)
     */
    public DG11File readDG11() {
        try {
            InputStream is = service.getInputStream(PassportService.EF_DG11);
            return new DG11File(is);
        } catch (Exception e) {
            Log.w(TAG, "DG11 not available: " + e.getMessage());
            return null;
        }
    }

    /**
     * Read DG12 (Additional Document Details)
     */
    public DG12File readDG12() {
        try {
            InputStream is = service.getInputStream(PassportService.EF_DG12);
            return new DG12File(is);
        } catch (Exception e) {
            Log.w(TAG, "DG12 not available: " + e.getMessage());
            return null;
        }
    }

    /**
     * Read DG14 (Security Options)
     */
    public DG14File readDG14() {
        try {
            InputStream is = service.getInputStream(PassportService.EF_DG14);
            return new DG14File(is);
        } catch (Exception e) {
            Log.w(TAG, "DG14 not available: " + e.getMessage());
            return null;
        }
    }

    /**
     * Read DG15 (Active Authentication Public Key)
     */
    public DG15File readDG15() {
        try {
            InputStream is = service.getInputStream(PassportService.EF_DG15);
            return new DG15File(is);
        } catch (Exception e) {
            Log.w(TAG, "DG15 not available: " + e.getMessage());
            return null;
        }
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : new ArrayList<>();
    }
}