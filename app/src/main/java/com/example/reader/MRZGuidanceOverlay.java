package com.example.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.DashPathEffect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class MRZGuidanceOverlay extends View {

    private Paint overlayPaint;
    private Paint borderPaint;
    private Paint cornerPaint;
    private Paint detectedCornerPaint;
    private Paint cornerLinePaint;
    private RectF guidanceRect;
    private Path overlayPath;

    private boolean isDocumentDetected = false;
    private boolean isAligned = false;
    private Point[] detectedCorners = null;  // Now using android.graphics.Point
    private float alignmentScore = 0f;

    private static final float CORNER_LENGTH = 40f;
    private static final float CORNER_WIDTH = 4f;
    private static final float BORDER_WIDTH = 2f;
    private static final float DETECTED_CORNER_RADIUS = 8f;

    private static final float ID_CARD_ASPECT_RATIO = 85.6f / 53.98f;
    private static final float PASSPORT_ASPECT_RATIO = 125f / 88f;

    private float documentAspectRatio = ID_CARD_ASPECT_RATIO;
    private static final float BOX_WIDTH_PERCENTAGE = 0.90f;

    private int borderColor = 0xFFFFFFFF;
    private boolean isScanning = true;

    public MRZGuidanceOverlay(Context context) {
        super(context);
        init();
    }

    public MRZGuidanceOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        overlayPaint = new Paint();
        overlayPaint.setColor(0xB0000000);
        overlayPaint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint();
        borderPaint.setColor(borderColor);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(BORDER_WIDTH);
        borderPaint.setAntiAlias(true);
        borderPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

        cornerPaint = new Paint();
        cornerPaint.setColor(borderColor);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(CORNER_WIDTH);
        cornerPaint.setAntiAlias(true);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);

        detectedCornerPaint = new Paint();
        detectedCornerPaint.setColor(0xFF00BFFF);
        detectedCornerPaint.setStyle(Paint.Style.FILL);
        detectedCornerPaint.setAntiAlias(true);

        cornerLinePaint = new Paint();
        cornerLinePaint.setColor(0xFF00BFFF);
        cornerLinePaint.setStyle(Paint.Style.STROKE);
        cornerLinePaint.setStrokeWidth(3f);
        cornerLinePaint.setAntiAlias(true);

        guidanceRect = new RectF();
        overlayPath = new Path();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGuidanceRect();
    }

    private void updateGuidanceRect() {
        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) {
            return;
        }

        float boxWidth = width * BOX_WIDTH_PERCENTAGE;
        float boxHeight = boxWidth / documentAspectRatio;

        float maxHeight = height * 0.70f;
        if (boxHeight > maxHeight) {
            boxHeight = maxHeight;
            boxWidth = boxHeight * documentAspectRatio;
        }

        float left = (width - boxWidth) / 2f;
        float top = (height - boxHeight) / 2f;
        float right = left + boxWidth;
        float bottom = top + boxHeight;

        guidanceRect.set(left, top, right, bottom);

        overlayPath.reset();
        overlayPath.addRect(0, 0, width, height, Path.Direction.CW);
        overlayPath.addRoundRect(guidanceRect, 16, 16, Path.Direction.CCW);
        overlayPath.setFillType(Path.FillType.EVEN_ODD);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (guidanceRect.isEmpty()) {
            return;
        }

        canvas.drawPath(overlayPath, overlayPaint);

        if (isScanning) {
            canvas.drawRoundRect(guidanceRect, 16, 16, borderPaint);
        }

        drawCorners(canvas);

        if (detectedCorners != null && detectedCorners.length == 4) {
            drawDetectedCorners(canvas);
        }

        if (isDocumentDetected) {
            drawAlignmentIndicator(canvas);
        }
    }

    private void drawCorners(Canvas canvas) {
        float left = guidanceRect.left;
        float top = guidanceRect.top;
        float right = guidanceRect.right;
        float bottom = guidanceRect.bottom;

        canvas.drawLine(left, top + CORNER_LENGTH, left, top, cornerPaint);
        canvas.drawLine(left, top, left + CORNER_LENGTH, top, cornerPaint);

        canvas.drawLine(right - CORNER_LENGTH, top, right, top, cornerPaint);
        canvas.drawLine(right, top, right, top + CORNER_LENGTH, cornerPaint);

        canvas.drawLine(left, bottom - CORNER_LENGTH, left, bottom, cornerPaint);
        canvas.drawLine(left, bottom, left + CORNER_LENGTH, bottom, cornerPaint);

        canvas.drawLine(right - CORNER_LENGTH, bottom, right, bottom, cornerPaint);
        canvas.drawLine(right, bottom, right, bottom - CORNER_LENGTH, cornerPaint);
    }

    private void drawDetectedCorners(Canvas canvas) {
        if (detectedCorners == null) return;

        int cornerColor;
        if (isAligned) {
            cornerColor = 0xFF00FF00;
        } else if (isDocumentDetected) {
            cornerColor = 0xFFFFAA00;
        } else {
            cornerColor = 0xFF00BFFF;
        }

        detectedCornerPaint.setColor(cornerColor);
        cornerLinePaint.setColor(cornerColor);

        // Draw lines connecting corners
        for (int i = 0; i < 4; i++) {
            int nextIndex = (i + 1) % 4;
            canvas.drawLine(
                    detectedCorners[i].x,
                    detectedCorners[i].y,
                    detectedCorners[nextIndex].x,
                    detectedCorners[nextIndex].y,
                    cornerLinePaint
            );
        }

        // Draw corner points
        for (Point corner : detectedCorners) {
            canvas.drawCircle(corner.x, corner.y,
                    DETECTED_CORNER_RADIUS, detectedCornerPaint);

            Paint innerPaint = new Paint(detectedCornerPaint);
            innerPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(corner.x, corner.y,
                    DETECTED_CORNER_RADIUS / 2, innerPaint);
        }
    }

    private void drawAlignmentIndicator(Canvas canvas) {
        float barWidth = guidanceRect.width() * 0.6f;
        float barHeight = 6f;
        float barLeft = guidanceRect.centerX() - barWidth / 2;
        float barTop = guidanceRect.bottom + 20;

        Paint bgPaint = new Paint();
        bgPaint.setColor(0x80FFFFFF);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(barLeft, barTop, barLeft + barWidth, barTop + barHeight,
                barHeight / 2, barHeight / 2, bgPaint);

        Paint progressPaint = new Paint();
        if (alignmentScore >= 0.8f) {
            progressPaint.setColor(0xFF00FF00);
        } else if (alignmentScore >= 0.5f) {
            progressPaint.setColor(0xFFFFAA00);
        } else {
            progressPaint.setColor(0xFFFF4444);
        }
        progressPaint.setStyle(Paint.Style.FILL);

        float progressWidth = barWidth * alignmentScore;
        canvas.drawRoundRect(barLeft, barTop, barLeft + progressWidth, barTop + barHeight,
                barHeight / 2, barHeight / 2, progressPaint);
    }

// In MRZGuidanceOverlay, the guide box should be centered horizontally
// and positioned based on the actual preview dimensions

    public RectF getGuidanceBoxRect() {
        int width = getWidth();
        int height = getHeight();

        // ID card aspect ratio is ~1.586 (85.6mm × 53.98mm)
        float cardAspectRatio = 1.586f;

        // Make the guide box 90% of screen width
        float boxWidth = width * 0.9f;
        float boxHeight = boxWidth / cardAspectRatio;

        // Center horizontally and vertically
        float left = (width - boxWidth) / 2f;
        float top = (height - boxHeight) / 2f;

        return new RectF(left, top, left + boxWidth, top + boxHeight);
    }

    public void setDocumentType(DocumentType type) {
        switch (type) {
            case ID_CARD:
                documentAspectRatio = ID_CARD_ASPECT_RATIO;
                break;
            case PASSPORT:
                documentAspectRatio = PASSPORT_ASPECT_RATIO;
                break;
        }
        updateGuidanceRect();
        invalidate();
    }

    public enum DocumentType {
        ID_CARD,
        PASSPORT
    }

    public void setAlignmentState(boolean documentDetected, boolean aligned,
                                  Point[] corners, float score) {
        this.isDocumentDetected = documentDetected;
        this.isAligned = aligned;
        this.alignmentScore = score;

        if (corners != null && corners.length == 4) {
            this.detectedCorners = corners;
        } else {
            this.detectedCorners = null;
        }

        if (aligned) {
            borderColor = 0xFF00FF00;
            cornerPaint.setColor(borderColor);
            borderPaint.setPathEffect(null);
            isScanning = false;
        } else if (documentDetected) {
            borderColor = 0xFFFFAA00;
            cornerPaint.setColor(borderColor);
            borderPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
            isScanning = true;
        } else {
            borderColor = 0xFFFFFFFF;
            cornerPaint.setColor(borderColor);
            borderPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
            isScanning = true;
        }

        borderPaint.setColor(borderColor);
        invalidate();
    }

    public void setDetectionState(boolean mrzDetected, int consecutiveCount, int requiredCount) {
        if (mrzDetected) {
            float progress = (float) consecutiveCount / requiredCount;
            int green = (int) (155 + 100 * progress);
            borderColor = 0xFF000000 | (green << 8);
            borderPaint.setPathEffect(null);
            isScanning = false;
        } else {
            borderColor = 0xFFFFFFFF;
            borderPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
            isScanning = true;
        }

        borderPaint.setColor(borderColor);
        cornerPaint.setColor(borderColor);
        invalidate();
    }

    public void setSuccessState() {
        borderColor = 0xFF00FF00;
        borderPaint.setColor(borderColor);
        borderPaint.setPathEffect(null);
        cornerPaint.setColor(borderColor);
        isScanning = false;

        detectedCorners = null;
        isDocumentDetected = false;
        isAligned = true;
        alignmentScore = 1.0f;

        invalidate();
    }

    public void updateDetectedCorners(Point[] corners) {
        this.detectedCorners = corners;
        invalidate();
    }
}