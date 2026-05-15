package com.nubian.ai.app;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UGroundClientTest {

    @Test
    void parsesJsonPoint() throws Exception {
        UGroundClient client = new UGroundClient();

        UGroundClient.GroundedPoint point = client.parsePoint("{\"x\":540,\"y\":920}", 1001, 1001);

        assertEquals(540, point.x());
        assertEquals(920, point.y());
    }

    @Test
    void parsesPlainCoordinateText() throws Exception {
        UGroundClient client = new UGroundClient();

        UGroundClient.GroundedPoint point = client.parsePoint("(540, 920)", 1001, 1001);

        assertEquals(540, point.x());
        assertEquals(920, point.y());
    }

    @Test
    void groundingPromptDisambiguatesChildSubmenus() {
        UGroundClient client = new UGroundClient();

        ObjectNode request = client.buildRequest(new byte[]{1, 2, 3},
                "target item inside the right child submenu panel");
        String text = request.toString();

        assertTrue(text.contains("child submenu panel"), text);
        assertTrue(text.contains("ignore the parent row"), text);
    }
}
