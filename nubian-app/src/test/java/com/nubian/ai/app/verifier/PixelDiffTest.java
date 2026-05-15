package com.nubian.ai.app.verifier;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PixelDiffTest {

    @Test
    void identicalImagesProduceNoBoxes() throws Exception {
        byte[] png = solidPng(64, 64, new Color(40, 40, 40));
        PixelDiff.Result result = PixelDiff.diff(png, png);
        assertTrue(result.boxes().isEmpty(), "identical images should produce zero changed regions");
        assertEquals("no change", result.renderForPrompt());
    }

    @Test
    void singleRectangleChangeProducesOneBox() throws Exception {
        byte[] before = solidPng(128, 128, new Color(20, 20, 20));
        byte[] after = solidPngWithRect(128, 128, new Color(20, 20, 20),
                40, 40, 30, 30, new Color(220, 220, 220));
        PixelDiff.Result result = PixelDiff.diff(before, after);
        assertEquals(1, result.boxes().size());
        PixelDiff.Box b = result.boxes().get(0);
        // Box is padded by 8 px and clamped to image bounds, so the actual changed
        // pixels (40..70 in x, 40..70 in y) should be inside the reported box.
        assertTrue(b.x() <= 40 && b.x() + b.w() >= 70, "x range should cover [40,70]: " + b);
        assertTrue(b.y() <= 40 && b.y() + b.h() >= 70, "y range should cover [40,70]: " + b);
    }

    @Test
    void mismatchedDimensionsAreResized() throws Exception {
        byte[] before = solidPng(128, 128, new Color(40, 40, 40));
        byte[] after = solidPng(64, 64, new Color(220, 220, 220));
        PixelDiff.Result result = PixelDiff.diff(before, after);
        // After resize the after-shot is now 128x128 white; whole frame changed.
        assertFalse(result.boxes().isEmpty());
        assertTrue(result.changedPixels() > 1000);
    }

    @Test
    void emptyOrNullInputsReturnEmptyResult() {
        assertTrue(PixelDiff.diff(null, new byte[]{1, 2, 3}).boxes().isEmpty());
        assertTrue(PixelDiff.diff(new byte[]{1, 2, 3}, null).boxes().isEmpty());
        assertTrue(PixelDiff.diff(new byte[0], new byte[0]).boxes().isEmpty());
    }

    @Test
    void renderForPromptIncludesBoxCoordinatesAndChangedPixelCount() throws Exception {
        byte[] before = solidPng(128, 128, new Color(20, 20, 20));
        byte[] after = solidPngWithRect(128, 128, new Color(20, 20, 20),
                40, 40, 30, 30, new Color(220, 220, 220));
        PixelDiff.Result result = PixelDiff.diff(before, after);
        String rendered = result.renderForPrompt();
        assertTrue(rendered.contains("region(s) changed"));
        assertTrue(rendered.contains("px above threshold"));
        assertTrue(rendered.contains("x="));
        assertTrue(rendered.contains("area="));
    }

    private static byte[] solidPng(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally { g.dispose(); }
        return encode(img);
    }

    private static byte[] solidPngWithRect(int w, int h, Color bg,
                                           int rx, int ry, int rw, int rh, Color rect) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(bg);
            g.fillRect(0, 0, w, h);
            g.setColor(rect);
            g.fillRect(rx, ry, rw, rh);
        } finally { g.dispose(); }
        return encode(img);
    }

    private static byte[] encode(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
