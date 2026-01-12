package com.example.reader.ocr;

import com.example.reader.utils.MRZUtils;
import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class OCRProcessor {

    public List<MRZCandidate> extractMRZCandidates(Text text, int viewHeight) {
        List<MRZCandidate> candidates = new ArrayList<>();

        if (viewHeight == 0) viewHeight = 1;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                MRZCandidate candidate = processLine(line, viewHeight);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }

        return rankAndFilterCandidates(candidates);
    }

    private MRZCandidate processLine(Text.Line line, int viewHeight) {
        String lineText = line.getText();
        int len = lineText.length();

        if (len < 20 || len > 50) return null;

        float confidence = getLineConfidence(line);
        if (confidence < 0.3f) return null;

        float yPos = line.getBoundingBox() != null ?
                1.0f - (line.getBoundingBox().exactCenterY() / viewHeight) : 0.5f;

        String quickClean = lineText.replace(" ", "").toUpperCase();

        if (MRZUtils.isQuickEEPCheck(quickClean) || MRZUtils.isQuickMRZCheck(quickClean)) {
            return new MRZCandidate(lineText, yPos, confidence);
        }

        return null;
    }

    private float getLineConfidence(Text.Line line) {
        try {
            Float conf = line.getConfidence();
            return conf != null ? conf : 0.5f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private List<MRZCandidate> rankAndFilterCandidates(List<MRZCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator
                        .comparing((MRZCandidate c) -> c.confidence).reversed()
                        .thenComparing(c -> c.yPosition, Comparator.reverseOrder()))
                .limit(3)
                .sorted(Comparator.comparing((MRZCandidate c) -> c.yPosition).reversed())
                .collect(Collectors.toList());
    }

    public static class MRZCandidate {
        public final String text;
        public final float yPosition;
        public final float confidence;

        public MRZCandidate(String text, float yPosition, float confidence) {
            this.text = text;
            this.yPosition = yPosition;
            this.confidence = confidence;
        }
    }
}