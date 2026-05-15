package com.nubian.ai.app.verifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Draws concentric circle overlays at each action point on a PNG screenshot.
 *
 * <p>Inner ring: radius 8 px, RGBA(255,220,0,180), stroke 2 px.
 * Outer ring: radius 24 px (3× inner), RGBA(255,40,40,120), stroke 3 px.
 */
public final class PngAnnotator {

    private static final Logger log = LoggerFactory.getLogger(PngAnnotator.class);

    private static final int INNER_RADIUS = 8;
    private static final int OUTER_RADIUS = 24; // 3× inner
    private static final int INNER_STROKE = 2;
    private static final int OUTER_STROKE = 3;

    /** Latest ring is fully opaque; oldest fades to FADE_FLOOR fraction of base alpha. */
    private static final double FADE_FLOOR = 0.35;

    /** Base alphas used when a single ring is drawn. Older rings are scaled below these. */
    private static final int INNER_ALPHA_FULL = 180;
    private static final int OUTER_ALPHA_FULL = 120;
    private static final int INNER_RGB = 0xFFDC00; // 255,220,0
    private static final int OUTER_RGB = 0xFF2828; // 255,40,40

    private PngAnnotator() {}

    /**
     * Annotate the given PNG bytes with concentric circles at each action point.
     *
     * @param rawPng raw PNG bytes
     * @param points action points to annotate
     * @return annotated PNG bytes; original bytes returned unchanged if points is empty or decode fails
     */
    public static byte[] annotate(byte[] rawPng, List<EvidenceStore.ActionPoint> points) {
        if (rawPng == null || rawPng.length == 0) return rawPng;
        if (points == null || points.isEmpty()) return rawPng;

        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(rawPng));
        } catch (Exception ex) {
            log.warn("[PngAnnotator] failed to decode PNG ({} bytes): {}", rawPng.length, ex.toString());
            return rawPng;
        }
        if (img == null) {
            log.warn("[PngAnnotator] ImageIO returned null for {} bytes", rawPng.length);
            return rawPng;
        }

        // Ensure we have an ARGB image so alpha channels render correctly
        BufferedImage argb;
        if (img.getType() == BufferedImage.TYPE_INT_ARGB) {
            argb = img;
        } else {
            argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D tmp = argb.createGraphics();
            tmp.drawImage(img, 0, 0, null);
            tmp.dispose();
        }

        Graphics2D g = argb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        FontMetrics fm = g.getFontMetrics();

        int n = points.size();
        for (int i = 0; i < n; i++) {
            EvidenceStore.ActionPoint pt = points.get(i);
            // Oldest ring (i=0) gets minimum alpha; newest (i=n-1) gets full alpha.
            double t = (n == 1) ? 1.0 : (double) i / (n - 1);
            double scale = FADE_FLOOR + (1.0 - FADE_FLOOR) * t;
            int innerA = (int) Math.round(INNER_ALPHA_FULL * scale);
            int outerA = (int) Math.round(OUTER_ALPHA_FULL * scale);
            Color outerC = new Color((OUTER_RGB >> 16) & 0xFF, (OUTER_RGB >> 8) & 0xFF, OUTER_RGB & 0xFF, outerA);
            Color innerC = new Color((INNER_RGB >> 16) & 0xFF, (INNER_RGB >> 8) & 0xFF, INNER_RGB & 0xFF, innerA);
            drawRing(g, pt.x(), pt.y(), OUTER_RADIUS, outerC, OUTER_STROKE);
            drawRing(g, pt.x(), pt.y(), INNER_RADIUS, innerC, INNER_STROKE);
            drawIndexLabel(g, fm, i + 1, pt.x(), pt.y(), innerA);
        }
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(argb, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            log.warn("[PngAnnotator] failed to encode annotated PNG: {}", ex.toString());
            return rawPng;
        }
    }

    private static void drawRing(Graphics2D g, int cx, int cy, int radius, Color color, int strokeWidth) {
        g.setColor(color);
        g.setStroke(new BasicStroke(strokeWidth));
        int d = radius * 2;
        g.drawOval(cx - radius, cy - radius, d, d);
    }

    /**
     * Draw a temporal-order index next to the click point. Placed up-and-right of the outer
     * ring so the click target itself stays visible. White fill on dark outline so the
     * label survives both light and dark backgrounds. Alpha matches the inner ring so
     * older indices fade with the rings they label.
     */
    private static void drawIndexLabel(Graphics2D g, FontMetrics fm, int index, int cx, int cy, int alpha) {
        String label = Integer.toString(index);
        int tx = cx + OUTER_RADIUS - fm.charWidth('0') / 2;
        int ty = cy - OUTER_RADIUS + fm.getAscent();
        Color outline = new Color(0, 0, 0, Math.min(255, alpha));
        Color fill = new Color(255, 255, 255, Math.min(255, alpha));
        g.setColor(outline);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                g.drawString(label, tx + dx, ty + dy);
            }
        }
        g.setColor(fill);
        g.drawString(label, tx, ty);
    }
}
