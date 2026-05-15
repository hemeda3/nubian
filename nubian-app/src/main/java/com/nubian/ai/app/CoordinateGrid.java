package com.nubian.ai.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

final class CoordinateGrid {

    private static final Logger log = LoggerFactory.getLogger(CoordinateGrid.class);
    private static final int FALLBACK_SIZE = 1024;
    private static final int STEP = 50;

    private CoordinateGrid() {}

    static byte[] forScreenshot(byte[] screenshotPng) {
        int width = FALLBACK_SIZE;
        int height = FALLBACK_SIZE;
        if (screenshotPng != null && screenshotPng.length > 0) {
            try {
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(screenshotPng));
                if (src != null && src.getWidth() > 0 && src.getHeight() > 0) {
                    width = src.getWidth();
                    height = src.getHeight();
                }
            } catch (Exception ex) {
                log.debug("[grid] could not inspect screenshot dimensions: {}", ex.toString());
            }
        }

        try {
            BufferedImage grid = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = grid.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));

                for (int x = 0; x <= width; x += STEP) {
                    int px = Math.min(x, width - 1);
                    drawLine(g, px, 0, px, height - 1, x % 100 == 0);
                    drawLabel(g, "x=" + x, px + 3, 15, width, height);
                    drawLabel(g, "x=" + x, px + 3, height - 8, width, height);
                }
                for (int y = 0; y <= height; y += STEP) {
                    int py = Math.min(y, height - 1);
                    drawLine(g, 0, py, width - 1, py, y % 100 == 0);
                    drawLabel(g, "y=" + y, 3, py + 15, width, height);
                    drawLabel(g, "y=" + y, width - 58, py + 15, width, height);
                }
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(grid, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            log.warn("[grid] could not generate coordinate grid: {}", ex.toString());
            return new byte[0];
        }
    }

    private static void drawLine(Graphics2D g, int x1, int y1, int x2, int y2, boolean major) {
        g.setColor(major ? Color.BLACK : new Color(140, 140, 140));
        g.setStroke(new BasicStroke(major ? 2f : 1f));
        g.drawLine(x1, y1, x2, y2);
    }

    private static void drawLabel(Graphics2D g, String text, int x, int y, int width, int height) {
        int px = Math.max(2, Math.min(width - 60, x));
        int py = Math.max(14, Math.min(height - 4, y));
        g.setColor(Color.WHITE);
        g.fillRect(px - 1, py - 12, 58, 15);
        g.setColor(Color.BLACK);
        g.drawString(text, px, py);
    }
}
