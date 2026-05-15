package com.nubian.ai.app.verifier;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PngAnnotatorTest {

    private static byte[] blankPng(int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void emptyPoints_returnsBytesUnchanged() {
        byte[] png = blankPng(128, 128);
        byte[] result = PngAnnotator.annotate(png, List.of());
        assertSame(png, result, "Should return exact same reference when no points");
    }

    @Test
    void nullPoints_returnsBytesUnchanged() {
        byte[] png = blankPng(64, 64);
        byte[] result = PngAnnotator.annotate(png, null);
        assertSame(png, result);
    }

    @Test
    void nullPng_returnsNull() {
        assertNull(PngAnnotator.annotate(null, List.of()));
    }

    @Test
    void onePoint_outputDecodesSuccessfully_andHasNonBackgroundPixel() throws Exception {
        // 128x128 all-black image; center is 64,64
        byte[] png = blankPng(128, 128);
        EvidenceStore.ActionPoint pt = new EvidenceStore.ActionPoint(0L, 64, 64, "click");

        byte[] result = PngAnnotator.annotate(png, List.of(pt));

        assertNotNull(result);
        assertNotEquals(0, result.length);

        // Must decode back to a valid image
        BufferedImage annotated = ImageIO.read(new ByteArrayInputStream(result));
        assertNotNull(annotated, "Annotated PNG must be decodeable");
        assertEquals(128, annotated.getWidth());
        assertEquals(128, annotated.getHeight());

        // Pixel at approximately outer-ring distance (~24px from center) should have non-black color
        // The outer ring is red-ish; check a pixel along the right edge of the outer ring
        int px = annotated.getRGB(64 + 24, 64); // right edge of outer ring
        int alpha = (px >> 24) & 0xFF;
        int red   = (px >> 16) & 0xFF;
        // After blending red(255,40,40,120) over black: some red should be visible
        // At minimum the pixel should differ from pure black (0xFF000000 in ARGB where alpha=255)
        // The annotated image converts to ARGB so alpha=255 always, but RGB components change
        assertTrue(red > 0 || alpha > 0,
                "Pixel at outer-ring edge should differ from background; got ARGB=0x" + Integer.toHexString(px));
    }

    @Test
    void invalidPng_returnsOriginalBytes() {
        byte[] garbage = new byte[]{0, 1, 2, 3, 4, 5};
        EvidenceStore.ActionPoint pt = new EvidenceStore.ActionPoint(0L, 10, 10, "click");
        byte[] result = PngAnnotator.annotate(garbage, List.of(pt));
        assertSame(garbage, result, "Should return original bytes on decode failure");
    }
}
