package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CoordinateGridTest {

    @Test
    void grid_matches_screenshot_dimensions() throws Exception {
        byte[] screenshot = png(320, 240);

        byte[] gridPng = CoordinateGrid.forScreenshot(screenshot);

        assertTrue(gridPng.length > 0);
        BufferedImage grid = ImageIO.read(new ByteArrayInputStream(gridPng));
        assertNotNull(grid);
        assertEquals(320, grid.getWidth());
        assertEquals(240, grid.getHeight());
        assertEquals(Color.BLACK.getRGB(), grid.getRGB(0, 0));
        assertEquals(Color.BLACK.getRGB(), grid.getRGB(100, 0));
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
