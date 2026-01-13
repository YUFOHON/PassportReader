package com.example.reader.utils;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import com.example.reader.MRZGuidanceOverlay;
import com.google.mlkit.vision.common.InputImage;

import org.opencv.core.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Utility class for bitmap operations including:
 * - Converting ImageProxy to Bitmap
 * - Cropping bitmaps based on guidance overlay
 * - Saving bitmaps to storage
 */
public class BitmapUtils {
    private static final String TAG = "BitmapUtils";

    /**
     * Converts an InputImage and ImageProxy to a Bitmap.
     * Handles YUV to RGB conversion and rotation.
     */
    public static Bitmap inputImageToBitmap(InputImage inputImage, ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();

            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            int rotation = inputImage.getRotationDegrees();

            Log.d(TAG, "📷 ImageProxy RAW: " + width + "x" + height + ", rotation: " + rotation + "°");

            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int yRowStride = planes[0].getRowStride();
            int uvRowStride = planes[1].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();

            int[] argbArray = new int[width * height];

            yBuffer.rewind();
            uBuffer.rewind();
            vBuffer.rewind();

            // YUV to RGB conversion
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int yIndex = y * yRowStride + x;
                    int uvX = x / 2;
                    int uvY = y / 2;
                    int uvIndex = uvY * uvRowStride + uvX * uvPixelStride;

                    int yValue = yBuffer.get(yIndex) & 0xFF;
                    int uValue = (uBuffer.get(uvIndex) & 0xFF) - 128;
                    int vValue = (vBuffer.get(uvIndex) & 0xFF) - 128;

                    // YUV to RGB conversion formula
                    int r = (int) (yValue + 1.402f * vValue);
                    int g = (int) (yValue - 0.344f * uValue - 0.714f * vValue);
                    int b = (int) (yValue + 1.772f * uValue);

                    // Clamp values to [0, 255]
                    r = clamp(r);
                    g = clamp(g);
                    b = clamp(b);

                    argbArray[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(argbArray, width, height, Bitmap.Config.ARGB_8888);
            Log.d(TAG, "🖼️ Pre-rotation: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            // Apply rotation if needed
            if (rotation != 0) {
                Bitmap rotated = rotateBitmap(bitmap, rotation);
                Log.d(TAG, "🖼️ Post-rotation: " + rotated.getWidth() + "x" + rotated.getHeight());
                return rotated;            }

            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error converting ImageProxy to Bitmap", e);
            return null;
        }
    }

    /**
     * Rotates a bitmap by the specified degrees.
     * Recycles the original bitmap after rotation.
     */
    public static Bitmap rotateBitmap(Bitmap original, int degrees) {
        if (degrees == 0) {
            return original;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);

        Bitmap rotated = Bitmap.createBitmap(
                original, 0, 0,
                original.getWidth(),
                original.getHeight(),
                matrix,
                true
        );

        if (rotated != original) {
            original.recycle();
        }

        return rotated;
    }

    /**
     * Crops a bitmap to match the guidance overlay area.
     * Includes padding around the crop area for better context.
     */
    public static Bitmap cropToGuidanceOverlay(
            Bitmap bitmap,
            MRZGuidanceOverlay overlay,
            PreviewView previewView
    ) {
        RectF guide = overlay.getGuidanceBoxRect();
        if (guide == null || guide.isEmpty()) return bitmap;

        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        int pw = previewView.getWidth();
        int ph = previewView.getHeight();

        float bitmapAspect = (float) bw / bh;
        float previewAspect = (float) pw / ph;

        float scale;
        float offsetX = 0f;
        float offsetY = 0f;

        if (bitmapAspect > previewAspect) {
            scale = (float) ph / bh;
            float scaledWidth = bw * scale;
            offsetX = (scaledWidth - pw) / 2f;
        } else {
            scale = (float) pw / bw;
            float scaledHeight = bh * scale;
            offsetY = (scaledHeight - ph) / 2f;
        }

        int left = (int) ((guide.left + offsetX) / scale);
        int top = (int) ((guide.top + offsetY) / scale);
        int right = (int) ((guide.right + offsetX) / scale);
        int bottom = (int) ((guide.bottom + offsetY) / scale);

        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(bw, right);
        bottom = Math.min(bh, bottom);

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) return bitmap;

        Log.d("BitmapUtils",
                "✅ Final crop: " + width + "x" + height +
                        " from " + bw + "x" + bh);

        return Bitmap.createBitmap(bitmap, left, top, width, height);
    }
    /**
     * Saves a bitmap to device storage.
     * Uses MediaStore API for Android Q+ and direct file access for older versions.
     */
    public static String saveBitmapToFile(Context context, Bitmap bitmap) {
        if (bitmap == null) {
            Log.w(TAG, "Cannot save null bitmap");
            return null;
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                return saveBitmapMediaStore(context, bitmap);
            } else {
                return saveBitmapLegacy(context, bitmap);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error saving bitmap", e);
            return null;
        }
    }

    /**
     * Saves bitmap using MediaStore API (Android Q+)
     */
    private static String saveBitmapMediaStore(Context context, Bitmap bitmap) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,
                "MRZ_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/MRZScanner");

        Uri uri = context.getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (uri != null) {
            try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
                    Log.d(TAG, "✅ Bitmap saved to MediaStore: " + uri);
                    return uri.toString();
                }
            }
        }

        throw new Exception("Failed to create MediaStore entry");
    }

    /**
     * Saves bitmap using legacy file system access (Pre-Android Q)
     */
    private static String saveBitmapLegacy(Context context, Bitmap bitmap) throws Exception {
        File directory = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "MRZScanner"
        );

        if (!directory.exists() && !directory.mkdirs()) {
            throw new Exception("Failed to create directory");
        }

        String fileName = "MRZ_" + System.currentTimeMillis() + ".jpg";
        File file = new File(directory, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
        }

        // Notify media scanner
        android.content.Intent mediaScanIntent =
                new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(Uri.fromFile(file));
        context.sendBroadcast(mediaScanIntent);

        Log.d(TAG, "✅ Bitmap saved to file: " + file.getAbsolutePath());
        return file.getAbsolutePath();
    }

    /**
     * Clamps an integer value to the range [0, 255]
     */
    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /**
     * Scales a bitmap to a maximum dimension while maintaining aspect ratio.
     */
    public static Bitmap scaleBitmap(Bitmap original, int maxDimension) {
        if (original == null) {
            return null;
        }

        int width = original.getWidth();
        int height = original.getHeight();

        if (width <= maxDimension && height <= maxDimension) {
            return original;
        }

        float scale = Math.min(
                (float) maxDimension / width,
                (float) maxDimension / height
        );

        int scaledWidth = Math.round(width * scale);
        int scaledHeight = Math.round(height * scale);

        Bitmap scaled = Bitmap.createScaledBitmap(
                original,
                scaledWidth,
                scaledHeight,
                true
        );

        if (scaled != original) {
            original.recycle();
        }

        return scaled;
    }

    /**
     * Helper class to calculate crop region from preview to bitmap coordinates
     */
    private static class CropCalculator {
        private final int bitmapWidth;
        private final int bitmapHeight;
        private final int previewWidth;
        private final int previewHeight;
        private final RectF guideBox;

        private final float scaleX;
        private final float scaleY;
        private final int bitmapOffsetX;
        private final int bitmapOffsetY;

        CropCalculator(int bitmapWidth, int bitmapHeight,
                       int previewWidth, int previewHeight,
                       RectF guideBox) {
            this.bitmapWidth = bitmapWidth;
            this.bitmapHeight = bitmapHeight;
            this.previewWidth = previewWidth;
            this.previewHeight = previewHeight;
            this.guideBox = guideBox;

            // Calculate aspect ratios
            float previewAspect = (float) previewWidth / previewHeight;
            float bitmapAspect = (float) bitmapWidth / bitmapHeight;

            // Calculate scale and offset based on aspect ratio differences
            if (bitmapAspect > previewAspect) {
                // Bitmap is wider - crop sides
                float visibleBitmapWidth = bitmapHeight * previewAspect;
                this.bitmapOffsetX = (int) ((bitmapWidth - visibleBitmapWidth) / 2);
                this.bitmapOffsetY = 0;
                this.scaleX = visibleBitmapWidth / previewWidth;
                this.scaleY = bitmapHeight / (float) previewHeight;
            } else {
                // Bitmap is taller - crop top/bottom
                float visibleBitmapHeight = bitmapWidth / previewAspect;
                this.bitmapOffsetX = 0;
                this.bitmapOffsetY = (int) ((bitmapHeight - visibleBitmapHeight) / 2);
                this.scaleX = bitmapWidth / (float) previewWidth;
                this.scaleY = visibleBitmapHeight / previewHeight;
            }
        }

        CropRegion calculateCropRegion() {
            // Convert guide box coordinates to bitmap coordinates
            int cropX = bitmapOffsetX + (int) (guideBox.left * scaleX);
            int cropY = bitmapOffsetY + (int) (guideBox.top * scaleY);
            int cropWidth = (int) (guideBox.width() * scaleX);
            int cropHeight = (int) (guideBox.height() * scaleY);

            // Add padding (15% on each side)
            int paddingX = (int) (cropWidth * 0.15f);
            int paddingY = (int) (cropHeight * 0.15f);

            cropX -= paddingX;
            cropY -= paddingY;
            cropWidth += (paddingX * 2);
            cropHeight += (paddingY * 2);

            // Clamp to bitmap boundaries
            cropX = Math.max(0, cropX);
            cropY = Math.max(0, cropY);
            cropWidth = Math.min(cropWidth, bitmapWidth - cropX);
            cropHeight = Math.min(cropHeight, bitmapHeight - cropY);

            return new CropRegion(cropX, cropY, cropWidth, cropHeight);
        }
    }

    /**
     * Represents a rectangular crop region
     */
    private static class CropRegion {
        final int x;
        final int y;
        final int width;
        final int height;

        CropRegion(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean isValid() {
            return width > 0 && height > 0;
        }
    }

    /**
     * Creates a copy of a bitmap
     */
    public static Bitmap copyBitmap(Bitmap source) {
        if (source == null) {
            return null;
        }
        return source.copy(source.getConfig(), true);
    }

    /**
     * Recycles a bitmap safely (checks for null and if already recycled)
     */
    public static void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    /**
     * Gets the memory size of a bitmap in bytes
     */
    public static long getBitmapSize(Bitmap bitmap) {
        if (bitmap == null) {
            return 0;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            return bitmap.getAllocationByteCount();
        } else {
            return bitmap.getByteCount();
        }
    }

    /**
     * Crops a bitmap with specified padding percentage
     */
    public static Bitmap cropWithPadding(Bitmap original, RectF cropRect, float paddingPercent) {
        if (original == null || cropRect == null) {
            return original;
        }

        try {
            int paddingX = (int) (cropRect.width() * paddingPercent);
            int paddingY = (int) (cropRect.height() * paddingPercent);

            int x = (int) cropRect.left - paddingX;
            int y = (int) cropRect.top - paddingY;
            int width = (int) cropRect.width() + (paddingX * 2);
            int height = (int) cropRect.height() + (paddingY * 2);

            // Clamp to bitmap boundaries
            x = Math.max(0, x);
            y = Math.max(0, y);
            width = Math.min(width, original.getWidth() - x);
            height = Math.min(height, original.getHeight() - y);

            if (width <= 0 || height <= 0) {
                return original;
            }

            return Bitmap.createBitmap(original, x, y, width, height);

        } catch (Exception e) {
            Log.e(TAG, "Error cropping bitmap with padding", e);
            return original;
        }
    }

    /**
     * Converts a bitmap to grayscale
     */
    public static Bitmap toGrayscale(Bitmap original) {
        if (original == null) {
            return null;
        }

        int width = original.getWidth();
        int height = original.getHeight();

        Bitmap grayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = original.getPixel(x, y);

                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                // Convert to grayscale using luminosity method
                int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);

                int newPixel = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                grayscale.setPixel(x, y, newPixel);
            }
        }

        return grayscale;
    }

    /**
     * Converts ImageProxy to high-resolution Bitmap
     * Handles both YUV_420_888 (from ImageAnalysis) and JPEG (from ImageCapture)
     */
    public static Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        if (imageProxy == null) return null;

        try {
            int format = imageProxy.getFormat();
            int rotation = imageProxy.getImageInfo().getRotationDegrees();

            Log.d(TAG, "🖼️  Converting ImageProxy:");
            Log.d(TAG, "   Format: " + format);
            Log.d(TAG, "   Size: " + imageProxy.getWidth() + "x" + imageProxy.getHeight());
            Log.d(TAG, "   Rotation: " + rotation + "°");
            Log.d(TAG, "   Planes: " + imageProxy.getPlanes().length);

            Bitmap bitmap;

            // JPEG format (from ImageCapture)
            if (format == ImageFormat.JPEG) {
                bitmap = imageProxyToBitmapJPEG(imageProxy);
            }
            // YUV format (from ImageAnalysis)
            else if (format == ImageFormat.YUV_420_888) {
                bitmap = imageProxyToBitmapYUV(imageProxy);
            }
            else {
                Log.e(TAG, "❌ Unsupported format: " + format);
                return null;
            }

            if (bitmap == null) {
                return null;
            }

            // Apply rotation if needed
            if (rotation != 0) {
                bitmap = rotateBitmap(bitmap, rotation);
            }

            Log.d(TAG, "✅ Final bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error converting ImageProxy", e);
            return null;
        }
    }

    /**
     * Convert JPEG ImageProxy to Bitmap
     */
    private static Bitmap imageProxyToBitmapJPEG(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

            if (bitmap == null) {
                Log.e(TAG, "❌ Failed to decode JPEG");
                return null;
            }

            Log.d(TAG, "✅ JPEG decoded: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error decoding JPEG", e);
            return null;
        }
    }

    /**
     * Convert YUV_420_888 ImageProxy to Bitmap
     */
    private static Bitmap imageProxyToBitmapYUV(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();

            if (planes.length < 3) {
                Log.e(TAG, "❌ Invalid YUV format - expected 3 planes, got " + planes.length);
                return null;
            }

            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();

            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int yRowStride = planes[0].getRowStride();
            int uvRowStride = planes[1].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();

            int[] argbArray = new int[width * height];

            yBuffer.rewind();
            uBuffer.rewind();
            vBuffer.rewind();

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int yIndex = y * yRowStride + x;
                    int uvX = x / 2;
                    int uvY = y / 2;
                    int uvIndex = uvY * uvRowStride + uvX * uvPixelStride;

                    int yValue = yBuffer.get(yIndex) & 0xFF;
                    int uValue = (uBuffer.get(uvIndex) & 0xFF) - 128;
                    int vValue = (vBuffer.get(uvIndex) & 0xFF) - 128;

                    int r = clamp((int) (yValue + 1.402f * vValue));
                    int g = clamp((int) (yValue - 0.344f * uValue - 0.714f * vValue));
                    int b = clamp((int) (yValue + 1.772f * uValue));

                    argbArray[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(argbArray, width, height, Bitmap.Config.ARGB_8888);
            Log.d(TAG, "✅ YUV converted: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error converting YUV", e);
            return null;
        }
    }

    /**
     * Crop bitmap to guidance area
     */
    public static Bitmap cropToGuidanceArea(Bitmap source, RectF guidanceRect) {
        try {
            int x = Math.max(0, (int) guidanceRect.left);
            int y = Math.max(0, (int) guidanceRect.top);
            int width = (int) (guidanceRect.right - guidanceRect.left);
            int height = (int) (guidanceRect.bottom - guidanceRect.top);

            if (width <= 0 || height <= 0 ||
                    x + width > source.getWidth() ||
                    y + height > source.getHeight()) {
                return null;
            }

            return Bitmap.createBitmap(source, x, y, width, height);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error cropping", e);
            return null;
        }
    }

    /**
     * Get guidance box coordinates in bitmap coordinate space
     */
    public static RectF getGuidanceBoxInBitmapCoords(Bitmap bitmap, MRZGuidanceOverlay guidanceOverlay, PreviewView previewView) {
        // Get guidance box rect in view coordinates
        RectF viewGuidanceRect = guidanceOverlay.getGuidanceBoxRect();

        int previewWidth = previewView.getWidth();
        int previewHeight = previewView.getHeight();
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();

        Log.d(TAG, "📐 Coordinate mapping:");
        Log.d(TAG, "   Preview: " + previewWidth + "x" + previewHeight);
        Log.d(TAG, "   Bitmap: " + bitmapWidth + "x" + bitmapHeight);
        Log.d(TAG, "   Guidance (view): " + viewGuidanceRect.toString());

        // Calculate scaling factors
        float scaleX = (float) bitmapWidth / previewWidth;
        float scaleY = (float) bitmapHeight / previewHeight;

        // Map to bitmap coordinates
        RectF bitmapRect = new RectF(
                viewGuidanceRect.left * scaleX,
                viewGuidanceRect.top * scaleY,
                viewGuidanceRect.right * scaleX,
                viewGuidanceRect.bottom * scaleY
        );

        // Ensure rect is within bitmap bounds
        bitmapRect.left = Math.max(0, bitmapRect.left);
        bitmapRect.top = Math.max(0, bitmapRect.top);
        bitmapRect.right = Math.min(bitmapWidth, bitmapRect.right);
        bitmapRect.bottom = Math.min(bitmapHeight, bitmapRect.bottom);

        return bitmapRect;
    }



    /**
     * Checks if a bitmap is valid (not null and not recycled)
     */
    public static boolean isValid(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled();
    }
}