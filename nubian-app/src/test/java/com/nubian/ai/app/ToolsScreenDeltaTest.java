package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolsScreenDeltaTest {

    @Test
    void reportsNoVisibleChangeForIdenticalImages() throws Exception {
        byte[] png = png(false);

        Map<String, String> meta = Tools.screenDelta(png, png);

        assertEquals("false", meta.get("visible_change"));
    }

    @Test
    void reportsVisibleChangeForLargePixelDelta() throws Exception {
        Map<String, String> meta = Tools.screenDelta(png(false), png(true));

        assertEquals("true", meta.get("visible_change"));
    }

    private static byte[] png(boolean withBlock) throws Exception {
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 128, 128);
            if (withBlock) {
                g.setColor(Color.BLACK);
                g.fillRect(24, 24, 80, 80);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
