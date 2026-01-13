package com.example.reader.detection;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.camera.view.PreviewView;

import com.example.reader.MRZGuidanceOverlay;
import com.example.reader.utils.BitmapUtils;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentAlignmentDetector {
    private static final String TAG = "AlignmentDetector";

    private static final int REQUIRED_CONSECUTIVE_FRAMES = 3;
    private static final long DETECTION_COOLDOWN_MS = 50;

    // Alignment thresholds
    private static final float POSITION_TOLERANCE = 0.15f;
    private static final float SIZE_TOLERANCE = 0.15f;
    private static final float IOU_THRESHOLD = 0.70f;

    // OpenCV refinement settings
    private static final int CANNY_THRESHOLD_LOW = 50;
    private static final int CANNY_THRESHOLD_HIGH = 150;
    private static final double MIN_CONTOUR_AREA_RATIO = 0.3;
    private static final double MAX_CONTOUR_AREA_RATIO = 0.95;
    private static final double CORNER_EPSILON_FACTOR = 0.02;

    private final MRZGuidanceOverlay guidanceOverlay;
    private final PreviewView previewView;
    private final Handler mainHandler;
    private final ObjectDetector objectDetector;
    private final ExecutorService executorService;

    private int consecutiveAlignmentCount = 0;
    private Point[] lastValidCorners = null;
    private long lastDetectionTime = 0;

    private volatile int cachedPreviewWidth = 0;
    private volatile int cachedPreviewHeight = 0;
    private volatile RectF cachedGuideBox = null;

    private boolean wasAlignedLastFrame = false;

    public DocumentAlignmentDetector(MRZGuidanceOverlay guidanceOverlay, PreviewView previewView) {
        this.guidanceOverlay = guidanceOverlay;
        this.previewView = previewView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executorService = Executors.newSingleThreadExecutor();

        ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build();

        this.objectDetector = ObjectDetection.getClient(options);

        updateCachedValues();
        Log.d(TAG, "✅ DocumentAlignmentDetector initialized with ML Kit + OpenCV Hybrid");
    }

    public void updateCachedValues() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateCacheOnMainThread();
        } else {
            mainHandler.post(this::updateCacheOnMainThread);
        }
    }

    private void updateCacheOnMainThread() {
        if (previewView != null) {
            cachedPreviewWidth = previewView.getWidth();
            cachedPreviewHeight = previewView.getHeight();
        }
        if (guidanceOverlay != null) {
            cachedGuideBox = guidanceOverlay.getGuidanceBoxRect();
        }
    }

    public AlignmentResult checkAlignment(Bitmap bitmap) {
        return checkAlignment(bitmap, 0);
    }

    public AlignmentResult checkAlignment(Bitmap bitmap, int rotationDegrees) {
        if (bitmap == null || guidanceOverlay == null || previewView == null) {
            return AlignmentResult.notReady();
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDetectionTime < DETECTION_COOLDOWN_MS) {
            if (lastValidCorners != null) {
                return new AlignmentResult(true, false, 0.5f, "Processing...", lastValidCorners);
            }
            return AlignmentResult.notReady();
        }
        lastDetectionTime = currentTime;

        mainHandler.post(this::updateCacheOnMainThread);

        if (cachedPreviewWidth == 0 || cachedPreviewHeight == 0 ||
                cachedGuideBox == null || cachedGuideBox.isEmpty()) {
            return AlignmentResult.notReady();
        }

        Bitmap orientedBitmap = bitmap;
        if (rotationDegrees != 0) {
            orientedBitmap = rotateBitmap(bitmap, rotationDegrees);
        }

        RectF guidanceRect = BitmapUtils.getGuidanceBoxInBitmapCoords(orientedBitmap, guidanceOverlay, previewView);

        Bitmap croppedBitmap = BitmapUtils.cropToGuidanceArea(orientedBitmap, guidanceRect);

        if (croppedBitmap == null) {
            return new AlignmentResult(false, false, 0, "Crop failed", null);
        }

        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "🔍 HYBRID ML + OPENCV ALIGNMENT CHECK");

        try {
            // STEP 1: ML Object Detection (rough box) - on CROPPED image
            RectF roughBoxInCropped = detectDocumentML(croppedBitmap);

            if (roughBoxInCropped == null) {
                Log.d(TAG, "⚠️ No document detected by ML");
                consecutiveAlignmentCount = 0;
                lastValidCorners = null;
                if (croppedBitmap != orientedBitmap) {
                    croppedBitmap.recycle();
                }
                return AlignmentResult.noDocument();
            }

            Log.d(TAG, "📦 ML rough box (in cropped): " + rectToString(roughBoxInCropped));

            // STEP 2: Crop ROI from the cropped bitmap
            Bitmap roiBitmap = cropROI(croppedBitmap, roughBoxInCropped);

            if (roiBitmap == null) {
                Log.d(TAG, "⚠️ Failed to crop ROI");
                if (croppedBitmap != orientedBitmap) {
                    croppedBitmap.recycle();
                }
                return AlignmentResult.noDocument();
            }

            // STEP 3: OpenCV edge detection + contour finding for refined corners
            Point[] refinedCornersInROI = refineWithOpenCV(roiBitmap);

            // STEP 4: Map corners from ROI → Cropped → Full Bitmap
            Point[] cornersInCropped;
            if (refinedCornersInROI != null) {
                cornersInCropped = mapCornersFromROI(refinedCornersInROI, roughBoxInCropped);
                Log.d(TAG, "✅ OpenCV refined 4 corners found");
            } else {
                // Fallback to rough box corners
                cornersInCropped = boundsToCorners(roughBoxInCropped);
                Log.d(TAG, "⚠️ OpenCV refinement failed, using ML box corners");
            }

            // Recycle ROI bitmap
            if (roiBitmap != croppedBitmap && roiBitmap != orientedBitmap) {
                roiBitmap.recycle();
            }

            // **KEY FIX**: Map corners from cropped bitmap to full bitmap coordinates
            Point[] cornersInFullBitmap = mapCornersFromCroppedToFull(cornersInCropped, guidanceRect);

            Log.d(TAG, "🗺️ Corners in full bitmap:");
            for (int i = 0; i < cornersInFullBitmap.length; i++) {
                Log.d(TAG, "   Corner " + i + ": " + cornersInFullBitmap[i]);
            }

            // STEP 5: Map corners from full bitmap to preview coordinates
            Point[] cornersInPreview = mapCornersToPreview(
                    cornersInFullBitmap,
                    orientedBitmap.getWidth(),
                    orientedBitmap.getHeight()
            );

            Log.d(TAG, "🗺️ Corners in preview:");
            for (int i = 0; i < cornersInPreview.length; i++) {
                Log.d(TAG, "   Corner " + i + ": " + cornersInPreview[i]);
            }

            lastValidCorners = cornersInPreview;

            // STEP 6: Create bounding rect from corners for alignment check
            RectF documentBoundsInPreview = cornersToRect(cornersInPreview);

            Log.d(TAG, "🔍 DEBUG - Size Comparison:");
            Log.d(TAG, "   Doc bounds: " + rectToString(documentBoundsInPreview));
            Log.d(TAG, "   Guide box: " + rectToString(cachedGuideBox));
            Log.d(TAG, "   Width ratio: " + (documentBoundsInPreview.width() / cachedGuideBox.width()));
            Log.d(TAG, "   Height ratio: " + (documentBoundsInPreview.height() / cachedGuideBox.height()));

            // STEP 7: Analyze alignment with guide box
            AlignmentAnalysis analysis = analyzeAlignment(documentBoundsInPreview, cachedGuideBox);

            Log.d(TAG, "📊 Alignment Analysis:");
            Log.d(TAG, "   IoU: " + String.format("%.2f", analysis.iou));
            Log.d(TAG, "   Position OK: " + analysis.positionOk);
            Log.d(TAG, "   Size OK: " + analysis.sizeOk);
            Log.d(TAG, "   Message: " + analysis.message);

            return processAnalysis(analysis, cornersInPreview);

        } catch (Exception e) {
            Log.e(TAG, "❌ Alignment check error", e);
            return AlignmentResult.error();
        } finally {
            if (croppedBitmap != orientedBitmap && croppedBitmap != null) {
                croppedBitmap.recycle();
            }
            if (orientedBitmap != bitmap && orientedBitmap != null) {
                orientedBitmap.recycle();
            }
            Log.d(TAG, "═══════════════════════════════════════\n");
        }
    }

    /**
     * **NEW METHOD**: Map corners from cropped bitmap coordinates to full bitmap coordinates
     */
    private Point[] mapCornersFromCroppedToFull(Point[] cornersInCropped, RectF guidanceRect) {
        Point[] cornersInFull = new Point[4];

        int offsetX = (int) guidanceRect.left;
        int offsetY = (int) guidanceRect.top;

        for (int i = 0; i < 4; i++) {
            cornersInFull[i] = new Point(
                    cornersInCropped[i].x + offsetX,
                    cornersInCropped[i].y + offsetY
            );
        }

        return cornersInFull;
    }

    /**
     * STEP 2: Crop ROI from bitmap based on ML detection
     */
    private Bitmap cropROI(Bitmap source, RectF box) {
        try {
            // Add padding to ensure we capture edges
            float padding = Math.min(box.width(), box.height()) * 0.1f;

            int left = Math.max(0, (int) (box.left - padding));
            int top = Math.max(0, (int) (box.top - padding));
            int right = Math.min(source.getWidth(), (int) (box.right + padding));
            int bottom = Math.min(source.getHeight(), (int) (box.bottom + padding));

            int width = right - left;
            int height = bottom - top;

            if (width <= 0 || height <= 0) {
                return null;
            }

            return Bitmap.createBitmap(source, left, top, width, height);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to crop ROI", e);
            return null;
        }
    }

    /**
     * STEP 3: OpenCV edge detection + contour finding
     * Returns 4 refined corner points in ROI coordinates
     */
    private Point[] refineWithOpenCV(Bitmap roiBitmap) {
        Mat src = null;
        Mat gray = null;
        Mat blurred = null;
        Mat edges = null;
        Mat dilated = null;

        try {
            // Convert bitmap to Mat
            src = new Mat();
            Utils.bitmapToMat(roiBitmap, src);

            // Convert to grayscale
            gray = new Mat();
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY);

            // Apply Gaussian blur to reduce noise
            blurred = new Mat();
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);

            // Canny edge detection
            edges = new Mat();
            Imgproc.Canny(blurred, edges, CANNY_THRESHOLD_LOW, CANNY_THRESHOLD_HIGH);

            // Dilate to close gaps in edges
            dilated = new Mat();
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(edges, dilated, kernel);
            kernel.release();

            // Find contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(dilated, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            hierarchy.release();

            if (contours.isEmpty()) {
                Log.d(TAG, "⚠️ No contours found");
                return null;
            }

            // Find the best quadrilateral contour
            double imageArea = roiBitmap.getWidth() * roiBitmap.getHeight();
            double minArea = imageArea * MIN_CONTOUR_AREA_RATIO;
            double maxArea = imageArea * MAX_CONTOUR_AREA_RATIO;

            MatOfPoint2f bestQuad = null;
            double bestScore = 0;

            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);

                // Filter by area
                if (area < minArea || area > maxArea) {
                    contour.release();
                    continue;
                }

                // Approximate contour to polygon
                MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
                double perimeter = Imgproc.arcLength(contour2f, true);
                double epsilon = CORNER_EPSILON_FACTOR * perimeter;

                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(contour2f, approx, epsilon, true);

                // We want exactly 4 corners (quadrilateral)
                if (approx.rows() == 4) {
                    // Check if it's convex
                    MatOfPoint approxInt = new MatOfPoint();
                    approx.convertTo(approxInt, CvType.CV_32S);

                    if (Imgproc.isContourConvex(approxInt)) {
                        // Score based on area and aspect ratio
                        double score = calculateQuadScore(approx, area, imageArea);

                        if (score > bestScore) {
                            if (bestQuad != null) bestQuad.release();
                            bestQuad = approx;
                            bestScore = score;
                        } else {
                            approx.release();
                        }
                    } else {
                        approx.release();
                    }
                    approxInt.release();
                } else {
                    approx.release();
                }

                contour2f.release();
                contour.release();
            }

            if (bestQuad == null) {
                Log.d(TAG, "⚠️ No valid quadrilateral found");
                return null;
            }

            // Extract and order corners
            Point[] corners = orderCorners(bestQuad);
            bestQuad.release();

            Log.d(TAG, "✅ Found 4 corners via OpenCV");
            for (int i = 0; i < corners.length; i++) {
                Log.d(TAG, "   Corner " + i + ": (" + corners[i].x + ", " + corners[i].y + ")");
            }

            return corners;

        } catch (Exception e) {
            Log.e(TAG, "❌ OpenCV refinement error", e);
            return null;
        } finally {
            // Release all Mats
            if (src != null) src.release();
            if (gray != null) gray.release();
            if (blurred != null) blurred.release();
            if (edges != null) edges.release();
            if (dilated != null) dilated.release();
        }
    }

    /**
     * Calculate score for a quadrilateral candidate
     */
    private double calculateQuadScore(MatOfPoint2f quad, double area, double imageArea) {
        // Score based on area ratio (prefer larger)
        double areaScore = area / imageArea;

        // Calculate aspect ratio
        org.opencv.core.Point[] points = quad.toArray();
        double width = Math.max(
                distance(points[0], points[1]),
                distance(points[2], points[3])
        );
        double height = Math.max(
                distance(points[1], points[2]),
                distance(points[3], points[0])
        );

        double aspectRatio = width / height;
        if (aspectRatio < 1) aspectRatio = 1 / aspectRatio;

        // ID card aspect ratio is ~1.586
        double aspectScore = 1.0 - Math.abs(aspectRatio - 1.586) / 1.0;
        aspectScore = Math.max(0, aspectScore);

        return areaScore * 0.4 + aspectScore * 0.6;
    }

    private double distance(org.opencv.core.Point p1, org.opencv.core.Point p2) {
        return Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
    }

    /**
     * Order corners: top-left, top-right, bottom-right, bottom-left
     */
    private Point[] orderCorners(MatOfPoint2f quad) {
        org.opencv.core.Point[] pts = quad.toArray();

        // Sort by y-coordinate first
        Arrays.sort(pts, Comparator.comparingDouble(p -> p.y));

        // Top two points (smallest y)
        org.opencv.core.Point[] topTwo = {pts[0], pts[1]};
        // Bottom two points (largest y)
        org.opencv.core.Point[] bottomTwo = {pts[2], pts[3]};

        // Sort top two by x (left to right)
        if (topTwo[0].x > topTwo[1].x) {
            org.opencv.core.Point temp = topTwo[0];
            topTwo[0] = topTwo[1];
            topTwo[1] = temp;
        }

        // Sort bottom two by x (left to right)
        if (bottomTwo[0].x > bottomTwo[1].x) {
            org.opencv.core.Point temp = bottomTwo[0];
            bottomTwo[0] = bottomTwo[1];
            bottomTwo[1] = temp;
        }

        // Order: TL, TR, BR, BL
        return new Point[]{
                new Point((int) topTwo[0].x, (int) topTwo[0].y),      // Top-Left
                new Point((int) topTwo[1].x, (int) topTwo[1].y),      // Top-Right
                new Point((int) bottomTwo[1].x, (int) bottomTwo[1].y), // Bottom-Right
                new Point((int) bottomTwo[0].x, (int) bottomTwo[0].y)  // Bottom-Left
        };
    }

    /**
     * STEP 4: Map corners from ROI coordinates back to cropped bitmap coordinates
     */
    private Point[] mapCornersFromROI(Point[] roiCorners, RectF roughBox) {
        float padding = Math.min(roughBox.width(), roughBox.height()) * 0.1f;
        int offsetX = (int) (roughBox.left - padding);
        int offsetY = (int) (roughBox.top - padding);

        Point[] croppedCorners = new Point[4];
        for (int i = 0; i < 4; i++) {
            croppedCorners[i] = new Point(
                    roiCorners[i].x + offsetX,
                    roiCorners[i].y + offsetY
            );
        }
        return croppedCorners;
    }

    /**
     * STEP 5: Map corners from full bitmap to preview coordinates
     */
    private Point[] mapCornersToPreview(Point[] bitmapCorners, int bitmapWidth, int bitmapHeight) {
        float bitmapAspect = (float) bitmapWidth / bitmapHeight;
        float previewAspect = (float) cachedPreviewWidth / cachedPreviewHeight;

        float scale;
        float offsetX = 0;
        float offsetY = 0;

        if (bitmapAspect > previewAspect) {
            // Bitmap is wider - fit to height
            scale = (float) cachedPreviewHeight / bitmapHeight;
            float scaledWidth = bitmapWidth * scale;
            offsetX = (scaledWidth - cachedPreviewWidth) / 2f;
        } else {
            // Bitmap is taller - fit to width
            scale = (float) cachedPreviewWidth / bitmapWidth;
            float scaledHeight = bitmapHeight * scale;
            offsetY = (scaledHeight - cachedPreviewHeight) / 2f;
        }

        Point[] previewCorners = new Point[4];
        for (int i = 0; i < 4; i++) {
            previewCorners[i] = new Point(
                    (int) (bitmapCorners[i].x * scale - offsetX),
                    (int) (bitmapCorners[i].y * scale - offsetY)
            );
        }

        Log.d(TAG, "🗺️ Coordinate Mapping:");
        Log.d(TAG, "   Bitmap: " + bitmapWidth + "x" + bitmapHeight + " (aspect: " + String.format("%.2f", bitmapAspect) + ")");
        Log.d(TAG, "   Preview: " + cachedPreviewWidth + "x" + cachedPreviewHeight + " (aspect: " + String.format("%.2f", previewAspect) + ")");
        Log.d(TAG, "   Scale: " + String.format("%.2f", scale) + ", OffsetX: " + String.format("%.2f", offsetX) + ", OffsetY: " + String.format("%.2f", offsetY));

        return previewCorners;
    }

    /**
     * Convert corners to bounding RectF
     */
    private RectF cornersToRect(Point[] corners) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (Point corner : corners) {
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
        }

        return new RectF(minX, minY, maxX, maxY);
    }

    private Point[] boundsToCorners(RectF bounds) {
        return new Point[]{
                new Point((int) bounds.left, (int) bounds.top),
                new Point((int) bounds.right, (int) bounds.top),
                new Point((int) bounds.right, (int) bounds.bottom),
                new Point((int) bounds.left, (int) bounds.bottom)
        };
    }

    /**
     * ML Kit Object Detection (rough box)
     */
    private RectF detectDocumentML(Bitmap bitmap) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            List<DetectedObject> results = Tasks.await(objectDetector.process(image));

            if (results.isEmpty()) {
                return null;
            }

            float imageArea = bitmap.getWidth() * bitmap.getHeight();
            DetectedObject bestDocument = null;
            float bestScore = 0;

            for (DetectedObject obj : results) {
                android.graphics.Rect bounds = obj.getBoundingBox();
                float area = bounds.width() * bounds.height();
                float areaRatio = area / imageArea;

                if (areaRatio < 0.10f || areaRatio > 0.90f) {
                    continue;
                }

                float aspectRatio = (float) bounds.width() / bounds.height();
                if (aspectRatio < 1) aspectRatio = 1 / aspectRatio;

                float score = 0;
                score += areaRatio * 0.3f;

                float aspectScore = 1f - Math.abs(aspectRatio - 1.586f) / 1.0f;
                score += Math.max(0, aspectScore) * 0.4f;

                float centerX = bounds.centerX() / (float) bitmap.getWidth();
                float centerY = bounds.centerY() / (float) bitmap.getHeight();
                float centerScore = 1f - (Math.abs(centerX - 0.5f) + Math.abs(centerY - 0.5f));
                score += centerScore * 0.3f;

                if (score > bestScore) {
                    bestScore = score;
                    bestDocument = obj;
                }
            }

            if (bestDocument != null) {
                android.graphics.Rect bounds = bestDocument.getBoundingBox();
                return new RectF(bounds);
            }

            return null;

        } catch (Exception e) {
            Log.e(TAG, "❌ ML Kit detection failed", e);
            return null;
        }
    }

    /**
     * Analyze alignment between document and guide box
     */
    private AlignmentAnalysis analyzeAlignment(RectF document, RectF guide) {
        float iou = calculateIoU(document, guide);

        float docCenterX = document.centerX();
        float docCenterY = document.centerY();
        float guideCenterX = guide.centerX();
        float guideCenterY = guide.centerY();

        float offsetX = docCenterX - guideCenterX;
        float offsetY = docCenterY - guideCenterY;

        float normalizedOffsetX = offsetX / guide.width();
        float normalizedOffsetY = offsetY / guide.height();

        float docArea = document.width() * document.height();
        float guideArea = guide.width() * guide.height();
        float areaRatio = docArea / guideArea;

        boolean positionOk = Math.abs(normalizedOffsetX) <= POSITION_TOLERANCE &&
                Math.abs(normalizedOffsetY) <= POSITION_TOLERANCE;

        boolean sizeOk = areaRatio >= 0.75f && areaRatio <= 1.30f;

        float positionScore = 1f - (Math.abs(normalizedOffsetX) + Math.abs(normalizedOffsetY)) / 2f;
        float sizeScore = 1f - Math.abs(1f - areaRatio);

        float score = (iou * 0.5f) + (positionScore * 0.3f) + (sizeScore * 0.2f);
        score = Math.max(0f, Math.min(1f, score));

        boolean isAligned = iou >= IOU_THRESHOLD && positionOk && sizeOk;

        String message = generateGuidanceMessage(
                normalizedOffsetX, normalizedOffsetY,
                areaRatio,
                iou, isAligned
        );

        return new AlignmentAnalysis(isAligned, score, iou, positionOk, sizeOk, message);
    }

    private float calculateIoU(RectF a, RectF b) {
        float intersectLeft = Math.max(a.left, b.left);
        float intersectTop = Math.max(a.top, b.top);
        float intersectRight = Math.min(a.right, b.right);
        float intersectBottom = Math.min(a.bottom, b.bottom);

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) {
            return 0f;
        }

        float intersectionArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop);
        float aArea = a.width() * a.height();
        float bArea = b.width() * b.height();
        float unionArea = aArea + bArea - intersectionArea;

        return intersectionArea / unionArea;
    }

    private String generateGuidanceMessage(float offsetX, float offsetY,
                                           float areaRatio,
                                           float iou, boolean isAligned) {
        if (isAligned) {
            return "Hold steady";
        }

        if (iou >= 0.75f) {
            return "Hold steady";
        }

        if (Math.abs(offsetY) > POSITION_TOLERANCE) {
            return offsetY > 0 ? "Move document UP ↑" : "Move document DOWN ↓";
        }

        if (Math.abs(offsetX) > POSITION_TOLERANCE) {
            return offsetX > 0 ? "Move document LEFT ←" : "Move document RIGHT →";
        }

        if (areaRatio < 0.80f) {
            return "Move closer to document";
        }
        if (areaRatio > 1.25f) {
            return "Move away from document";
        }

        if (iou < IOU_THRESHOLD) {
            return "Align document with frame";
        }

        return "Hold steady";
    }

    private AlignmentResult processAnalysis(AlignmentAnalysis analysis, Point[] corners) {
        // Hysteresis: use lower threshold to MAINTAIN alignment, higher to GAIN it
        float effectiveIouThreshold = wasAlignedLastFrame ? 0.65f : IOU_THRESHOLD;

        boolean isAligned = analysis.iou >= effectiveIouThreshold &&
                analysis.positionOk &&
                (wasAlignedLastFrame || analysis.sizeOk);

        if (isAligned) {
            consecutiveAlignmentCount++;
            wasAlignedLastFrame = true;
            Log.d(TAG, "✅ Aligned! Count: " + consecutiveAlignmentCount + "/" + REQUIRED_CONSECUTIVE_FRAMES);

            if (consecutiveAlignmentCount >= REQUIRED_CONSECUTIVE_FRAMES) {
                Log.d(TAG, "🎯 ALIGNMENT COMPLETE - Stopping detector");

                return new AlignmentResult(true, true, analysis.score,
                        "Document aligned!", corners);
            }
            return new AlignmentResult(true, false, analysis.score,
                    "Hold steady (" + consecutiveAlignmentCount + "/" +
                            REQUIRED_CONSECUTIVE_FRAMES + ")", corners);
        } else {
            consecutiveAlignmentCount = 0;
            wasAlignedLastFrame = false;
            return new AlignmentResult(true, false, analysis.score,
                    analysis.message, corners);
        }
    }

    private Bitmap rotateBitmap(Bitmap source, int degrees) {
        if (degrees == 0) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        try {
            return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "❌ OOM rotating bitmap", e);
            return source;
        }
    }

    private String rectToString(RectF r) {
        return String.format("[%.0f,%.0f - %.0f,%.0f] (%.0fx%.0f)",
                r.left, r.top, r.right, r.bottom, r.width(), r.height());
    }

    public void reset() {
        consecutiveAlignmentCount = 0;
        lastValidCorners = null;
        wasAlignedLastFrame = false;
    }

    public PreviewView getPreviewView() {
        return previewView;
    }

    public void cleanup() {
        objectDetector.close();
        executorService.shutdown();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INNER CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    private static class AlignmentAnalysis {
        final boolean isAligned;
        final float score;
        final float iou;
        final boolean positionOk;
        final boolean sizeOk;
        final String message;

        AlignmentAnalysis(boolean isAligned, float score, float iou,
                          boolean positionOk, boolean sizeOk, String message) {
            this.isAligned = isAligned;
            this.score = score;
            this.iou = iou;
            this.positionOk = positionOk;
            this.sizeOk = sizeOk;
            this.message = message;
        }
    }

    public static class AlignmentResult {
        public final boolean documentDetected;
        public final boolean isAligned;
        public final float alignmentScore;
        public final String message;
        public final Point[] corners;

        public AlignmentResult(boolean documentDetected, boolean isAligned,
                               float alignmentScore, String message, Point[] corners) {
            this.documentDetected = documentDetected;
            this.isAligned = isAligned;
            this.alignmentScore = alignmentScore;
            this.message = message;
            this.corners = corners;
        }

        public static AlignmentResult notReady() {
            return new AlignmentResult(false, false, 0f, "Initializing...", null);
        }

        public static AlignmentResult noDocument() {
            return new AlignmentResult(false, false, 0f, "Position document in frame", null);
        }

        public static AlignmentResult error() {
            return new AlignmentResult(false, false, 0f, "Detection error", null);
        }
    }
}