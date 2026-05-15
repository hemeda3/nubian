package com.nubian.ai.app.verifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pixel-level diff between two screenshots, returning bounding boxes around the regions
 * that actually changed. Used as a third authoritative channel for the verifier alongside
 * windows_diff and apps_installed_diff: when the OS-level windows/apps lists agree on
 * "no change", pixel_diff still distinguishes "the screen really did not change" from
 * "the agent did something the OS-level diff doesn't see" (intra-window state, file
 * dialog, document content edits, etc).
 *
 * <p>Algorithm (luminance / Y mode, fixed parameters tuned on representative pairs):
 * <ol>
 *   <li>Resize after-shot to before-shot dimensions if they differ.</li>
 *   <li>Per-pixel |Y_a - Y_b| where Y = 0.299 R + 0.587 G + 0.114 B.</li>
 *   <li>3x3 box blur on the score map to absorb single-pixel noise.</li>
 *   <li>Threshold at 12/255.</li>
 *   <li>5x5 close (dilate then erode) to fuse strokes of the same UI element.</li>
 *   <li>One round of 3x3 dilate for safety.</li>
 *   <li>Connected-component labelling, 8-connectivity.</li>
 *   <li>Drop components below {@code MIN_AREA = 80} pixels.</li>
 *   <li>Merge boxes within {@code MERGE_GAP = 8} pixels of each other.</li>
 *   <li>Pad each box by {@code PAD_BOX = 8} pixels and clamp to image bounds.</li>
 * </ol>
 *
 * <p>Pure Java, no native deps. ~75 ms on 1024x1024.
 */
public final class PixelDiff {

    private static final Logger log = LoggerFactory.getLogger(PixelDiff.class);

    private static final int BLUR_KERNEL = 3;
    private static final int THRESHOLD = 12;
    private static final int CLOSE_KERNEL = 5;
    private static final int DILATE_PASSES = 1;
    private static final int MIN_AREA = 80;
    private static final int MERGE_GAP = 8;
    private static final int PAD_BOX = 8;

    /** A bounding box around a changed region, in absolute pixel coordinates of the after-shot. */
    public record Box(int x, int y, int w, int h, int area) {}

    public record Result(List<Box> boxes, int width, int height, int changedPixels) {
        public boolean isEmpty() { return boxes.isEmpty(); }

        public String renderForPrompt() {
            if (boxes.isEmpty()) return "no change";
            StringBuilder sb = new StringBuilder();
            sb.append(boxes.size()).append(" region(s) changed (")
                    .append(changedPixels).append(" px above threshold)");
            int idx = 1;
            for (Box b : boxes) {
                sb.append("\n  ").append(idx++).append(". x=").append(b.x())
                        .append(" y=").append(b.y())
                        .append(" w=").append(b.w())
                        .append(" h=").append(b.h())
                        .append(" area=").append(b.area());
            }
            return sb.toString();
        }
    }

    private PixelDiff() {}

    /** Decode two PNG byte streams and run the diff. Returns an empty result on any decode failure. */
    public static Result diff(byte[] beforePng, byte[] afterPng) {
        if (beforePng == null || beforePng.length == 0 || afterPng == null || afterPng.length == 0) {
            return new Result(List.of(), 0, 0, 0);
        }
        BufferedImage before;
        BufferedImage after;
        try {
            before = readRgb(beforePng);
            after = readRgb(afterPng);
        } catch (Exception ex) {
            log.warn("[PixelDiff] decode failed: {}", ex.toString());
            return new Result(List.of(), 0, 0, 0);
        }
        if (before == null || after == null) return new Result(List.of(), 0, 0, 0);
        if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
            after = resize(after, before.getWidth(), before.getHeight());
        }
        return diff(before, after);
    }

    /** Run the diff on two already-decoded RGB BufferedImages of identical dimensions. */
    public static Result diff(BufferedImage before, BufferedImage after) {
        int width = before.getWidth();
        int height = before.getHeight();
        byte[] score = computeYScore(before, after, width, height);
        if (BLUR_KERNEL > 1) score = boxBlur(score, width, height, BLUR_KERNEL);
        byte[] mask = threshold(score, THRESHOLD);
        int changed = countNonZero(mask);
        if (CLOSE_KERNEL > 1) mask = erode(dilate(mask, width, height, CLOSE_KERNEL), width, height, CLOSE_KERNEL);
        for (int i = 0; i < DILATE_PASSES; i++) mask = dilate(mask, width, height, 3);
        List<Box> boxes = connectedComponents(mask, width, height, MIN_AREA);
        boxes = mergeBoxes(boxes, MERGE_GAP);
        boxes = padBoxes(boxes, PAD_BOX, width, height);
        boxes.sort(Comparator.comparingInt(Box::area).reversed());
        return new Result(boxes, width, height, changed);
    }

    private static BufferedImage readRgb(byte[] png) throws Exception {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
        if (source == null) return null;
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try { g.drawImage(source, 0, 0, null); } finally { g.dispose(); }
        return rgb;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, height, null);
        } finally { g.dispose(); }
        return out;
    }

    private static byte[] computeYScore(BufferedImage a, BufferedImage b, int width, int height) {
        byte[] out = new byte[width * height];
        int p = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgbA = a.getRGB(x, y);
                int rgbB = b.getRGB(x, y);
                int ya = ((((rgbA >>> 16) & 0xff) * 299) + (((rgbA >>> 8) & 0xff) * 587) + ((rgbA & 0xff) * 114) + 500) / 1000;
                int yb = ((((rgbB >>> 16) & 0xff) * 299) + (((rgbB >>> 8) & 0xff) * 587) + ((rgbB & 0xff) * 114) + 500) / 1000;
                int diff = ya - yb;
                out[p++] = (byte) (diff < 0 ? -diff : diff);
            }
        }
        return out;
    }

    private static byte[] boxBlur(byte[] source, int width, int height, int kernel) {
        byte[] out = new byte[source.length];
        int radius = kernel / 2;
        for (int y = 0; y < height; y++) {
            int yMin = Math.max(0, y - radius);
            int yMax = Math.min(height - 1, y + radius);
            for (int x = 0; x < width; x++) {
                int xMin = Math.max(0, x - radius);
                int xMax = Math.min(width - 1, x + radius);
                int sum = 0;
                int count = 0;
                for (int yy = yMin; yy <= yMax; yy++) {
                    int row = yy * width;
                    for (int xx = xMin; xx <= xMax; xx++) {
                        sum += source[row + xx] & 0xff;
                        count++;
                    }
                }
                out[y * width + x] = (byte) (sum / count);
            }
        }
        return out;
    }

    private static byte[] threshold(byte[] score, int t) {
        byte[] out = new byte[score.length];
        for (int i = 0; i < score.length; i++) out[i] = (byte) ((score[i] & 0xff) > t ? 255 : 0);
        return out;
    }

    private static int countNonZero(byte[] mask) {
        int n = 0;
        for (byte b : mask) if ((b & 0xff) != 0) n++;
        return n;
    }

    private static byte[] dilate(byte[] source, int width, int height, int kernel) {
        byte[] out = new byte[source.length];
        int radius = kernel / 2;
        for (int y = 0; y < height; y++) {
            int yMin = Math.max(0, y - radius);
            int yMax = Math.min(height - 1, y + radius);
            for (int x = 0; x < width; x++) {
                int xMin = Math.max(0, x - radius);
                int xMax = Math.min(width - 1, x + radius);
                boolean hit = false;
                outer:
                for (int yy = yMin; yy <= yMax; yy++) {
                    int row = yy * width;
                    for (int xx = xMin; xx <= xMax; xx++) {
                        if ((source[row + xx] & 0xff) != 0) { hit = true; break outer; }
                    }
                }
                out[y * width + x] = (byte) (hit ? 255 : 0);
            }
        }
        return out;
    }

    private static byte[] erode(byte[] source, int width, int height, int kernel) {
        byte[] out = new byte[source.length];
        int radius = kernel / 2;
        for (int y = 0; y < height; y++) {
            int yMin = Math.max(0, y - radius);
            int yMax = Math.min(height - 1, y + radius);
            for (int x = 0; x < width; x++) {
                int xMin = Math.max(0, x - radius);
                int xMax = Math.min(width - 1, x + radius);
                boolean all = true;
                outer:
                for (int yy = yMin; yy <= yMax; yy++) {
                    int row = yy * width;
                    for (int xx = xMin; xx <= xMax; xx++) {
                        if ((source[row + xx] & 0xff) == 0) { all = false; break outer; }
                    }
                }
                out[y * width + x] = (byte) (all ? 255 : 0);
            }
        }
        return out;
    }

    private static List<Box> connectedComponents(byte[] mask, int width, int height, int minArea) {
        boolean[] seen = new boolean[mask.length];
        int[] queue = new int[mask.length];
        List<Box> boxes = new ArrayList<>();
        for (int start = 0; start < mask.length; start++) {
            if (seen[start] || (mask[start] & 0xff) == 0) continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int area = 0;
            int minX = width;
            int minY = height;
            int maxX = 0;
            int maxY = 0;
            while (head < tail) {
                int p = queue[head++];
                int y = p / width;
                int x = p - y * width;
                area++;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                int yMin = Math.max(0, y - 1);
                int yMax = Math.min(height - 1, y + 1);
                int xMin = Math.max(0, x - 1);
                int xMax = Math.min(width - 1, x + 1);
                for (int ny = yMin; ny <= yMax; ny++) {
                    int row = ny * width;
                    for (int nx = xMin; nx <= xMax; nx++) {
                        int n = row + nx;
                        if (!seen[n] && (mask[n] & 0xff) != 0) {
                            seen[n] = true;
                            queue[tail++] = n;
                        }
                    }
                }
            }
            if (area >= minArea) {
                boxes.add(new Box(minX, minY, maxX - minX + 1, maxY - minY + 1, area));
            }
        }
        return boxes;
    }

    private static List<Box> mergeBoxes(List<Box> boxes, int gap) {
        if (gap <= 0 || boxes.size() < 2) return boxes;
        List<Box> merged = new ArrayList<>();
        for (Box box : boxes) {
            Box current = box;
            boolean changed = true;
            while (changed) {
                changed = false;
                List<Box> next = new ArrayList<>();
                for (Box other : merged) {
                    if (closeEnough(current, other, gap)) {
                        current = union(current, other);
                        changed = true;
                    } else {
                        next.add(other);
                    }
                }
                merged = next;
            }
            merged.add(current);
        }
        return merged;
    }

    private static boolean closeEnough(Box a, Box b, int gap) {
        int ax1 = a.x() - gap;
        int ay1 = a.y() - gap;
        int ax2 = a.x() + a.w() + gap;
        int ay2 = a.y() + a.h() + gap;
        int bx2 = b.x() + b.w();
        int by2 = b.y() + b.h();
        return ax1 <= bx2 && ax2 >= b.x() && ay1 <= by2 && ay2 >= b.y();
    }

    private static Box union(Box a, Box b) {
        int x1 = Math.min(a.x(), b.x());
        int y1 = Math.min(a.y(), b.y());
        int x2 = Math.max(a.x() + a.w(), b.x() + b.w());
        int y2 = Math.max(a.y() + a.h(), b.y() + b.h());
        return new Box(x1, y1, x2 - x1, y2 - y1, a.area() + b.area());
    }

    private static List<Box> padBoxes(List<Box> boxes, int padding, int width, int height) {
        if (padding <= 0) return boxes;
        List<Box> out = new ArrayList<>(boxes.size());
        for (Box b : boxes) {
            int x1 = Math.max(0, b.x() - padding);
            int y1 = Math.max(0, b.y() - padding);
            int x2 = Math.min(width, b.x() + b.w() + padding);
            int y2 = Math.min(height, b.y() + b.h() + padding);
            out.add(new Box(x1, y1, x2 - x1, y2 - y1, b.area()));
        }
        return out;
    }
}
