package com.nubian.ai.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentCompletionVerificationTest {

    private static final class StubSandbox extends Sandbox {
        StubSandbox() {
            super("http://stub:0", 1000);
        }

        @Override
        public Response hands(String type, Map<String, Object> args) {
            return new Response(true, 200, "{\"ok\":true}", null);
        }

        @Override
        public byte[] screenshot() {
            try {
                BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                return out.toByteArray();
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }
    }

    @Test
    void done_after_gui_action_is_rejected_when_visual_verifier_says_no() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int n = calls.incrementAndGet();
            String content = n == 1
                    ? "<tools><tool name=\"click\"><reason>try target</reason><args>{\"x\":10,\"y\":20}</args></tool>"
                    + "<tool name=\"done\"><reason>claim done</reason><args>{\"summary\":\"done\"}</args></tool></tools>"
                    : "{\"ok\":false,\"reason\":\"requested tool is not visibly selected\"}";
            String body = "{\"choices\":[{\"message\":{\"content\":" + quote(content)
                    + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            LlmClient llm = new LlmClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key", "text", "vision", 1000, 1000, 1000, 0);
            Agent agent = new Agent(llm, new Tools(new StubSandbox(), new Prompts(), null),
                    null, new Prompts(), "<<default>>", 1);
            List<Events.Event> events = new ArrayList<>();

            Agent.Result result = agent.run("run-test", "In the active editor, pick the requested tool.", events::add);

            assertFalse(result.success());
            assertTrue(events.stream().anyMatch(e -> "completion_rejected".equals(e.type())));
            assertTrue(events.stream().noneMatch(e -> "task_completed".equals(e.type())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void verifier_json_parser_is_conservative() {
        assertTrue(Agent.parseVerification("{\"ok\":true,\"reason\":\"looks selected\"}").ok());
        assertFalse(Agent.parseVerification("{\"ok\":false,\"reason\":\"not selected\"}").ok());
        assertFalse(Agent.parseVerification("not sure").ok());
    }

    @Test
    void prose_only_reply_is_inconclusive_not_reject() {
        // The Firefox-close trace bug: model emitted prose that concluded the
        // request was satisfied, but without a JSON envelope. The old parser
        // defaulted such replies to REJECT, silently inverting the verdict.
        // The new parser must mark them inconclusive so the runtime can decide.
        Agent.Verification v = Agent.parseVerification(
                "Firefox is not running, but the request was to close it. "
              + "windows_diff indicates that Firefox was closed. "
              + "Therefore, the request is satisfied.");
        assertTrue(v.isInconclusive(), "prose-only reply must be inconclusive");
        assertFalse(v.ok(), "inconclusive carries ok=false but is not REJECT");
    }

    @Test
    void json_with_non_boolean_ok_is_inconclusive() {
        Agent.Verification v = Agent.parseVerification("{\"ok\":\"yes\",\"reason\":\"close enough\"}");
        assertTrue(v.isInconclusive(),
                "string ok must be inconclusive, not silently flipped to REJECT");
    }

    @Test
    void empty_reply_is_inconclusive() {
        Agent.Verification v = Agent.parseVerification("");
        assertTrue(v.isInconclusive());
        assertEquals(Agent.Verification.Status.INCONCLUSIVE_AFTER_BUDGET, v.status());
    }

    @Test
    void wellformed_accept_keeps_accept_status() {
        Agent.Verification v = Agent.parseVerification(
                "{\"ok\":true,\"reason\":\"running_windows excludes Firefox\"}");
        assertEquals(Agent.Verification.Status.ACCEPT, v.status());
    }

    @Test
    void wellformed_reject_keeps_reject_status() {
        Agent.Verification v = Agent.parseVerification(
                "{\"ok\":false,\"reason\":\"running_windows still lists Firefox\"}");
        assertEquals(Agent.Verification.Status.REJECT, v.status());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }
}
