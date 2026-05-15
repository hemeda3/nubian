package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientTest {

    @Test
    void userImages_serializes_multiple_image_parts() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildBody("vision-model", List.of(
                LlmClient.Message.userImages("see both", List.of(new byte[]{1, 2}, new byte[]{3, 4}))
        ), 0, 100);

        assertTrue(body.path("messages").path(0).path("content").asText()
                .contains("goal_link"));
        JsonNode content = body.path("messages").path(1).path("content");
        assertEquals("text", content.path(0).path("type").asText());
        assertEquals("image_url", content.path(1).path("type").asText());
        assertEquals("image_url", content.path(2).path("type").asText());
        assertTrue(content.path(1).path("image_url").path("url").asText().startsWith("data:image/png;base64,"));
        assertTrue(content.path(2).path("image_url").path("url").asText().startsWith("data:image/png;base64,"));
        assertEquals(3, content.size());
    }

    @Test
    void geminiBody_usesGenerateContentShapeWithImagesAndThinkingBudget() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildGeminiBody("gemini-2.5-flash-lite", List.of(
                LlmClient.Message.system("JSON only"),
                LlmClient.Message.userImages("see this", List.of(new byte[]{1, 2}))
        ), 0.0, 200);

        String system = body.path("systemInstruction").path("parts").path(0).path("text").asText();
        assertTrue(system.contains("JSON only"));
        assertTrue(system.contains("goal_link"));
        JsonNode parts = body.path("contents").path(0).path("parts");
        assertEquals("see this", parts.path(0).path("text").asText());
        assertEquals("image/png", parts.path(1).path("inlineData").path("mimeType").asText());
        assertEquals("MEDIA_RESOLUTION_HIGH", parts.path(1).path("mediaResolution").path("level").asText());
        assertEquals(200, body.path("generationConfig").path("maxOutputTokens").asInt());
        assertEquals(8192, body.path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt());
        assertEquals(0.0, body.path("generationConfig").path("temperature").asDouble());
    }

    @Test
    void openRouterGeminiReasoningBudgetUsesEightKFloor() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildBody("google/gemini-2.5-flash-lite",
                List.of(LlmClient.Message.user("verify")), 0.0, 200);

        assertEquals(8192, body.path("reasoning").path("max_tokens").asInt());
    }

    @Test
    void geminiBody_usesJsonMimeTypeForJsonOnlyPrompts() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildGeminiBody("gemini-2.5-flash-lite", List.of(
                LlmClient.Message.system("Output only JSON: {\"ok\":true}."),
                LlmClient.Message.user("verify")
        ), 0.0, 200);

        assertEquals("application/json",
                body.path("generationConfig").path("responseMimeType").asText());
    }

    @Test
    void geminiResponse_parserExtractsTextAndUsage() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);
        String json = """
                {
                  "candidates": [{
                    "content": {"parts": [{"text": "{\\"action\\":\\"wait\\",\\"ms\\":500}"}]},
                    "finishReason": "STOP"
                  }],
                  "usageMetadata": {
                    "promptTokenCount": 10,
                    "candidatesTokenCount": 4,
                    "totalTokenCount": 14
                  }
                }
                """;

        LlmClient.Reply reply = client.parseGeminiReply(json);

        assertEquals("{\"action\":\"wait\",\"ms\":500}", reply.text());
        assertEquals("STOP", reply.finishReason());
        assertEquals(10, reply.promptTokens());
        assertEquals(4, reply.completionTokens());
        assertEquals(14, reply.totalTokens());
    }

    @Test
    void geminiModelIdsNormalizeFromOpenRouterProviderPrefix() {
        assertTrue(LlmClient.isGeminiModel("google/gemini-2.5-flash-lite"));
        assertTrue(LlmClient.isGeminiModel("gemini-2.5-flash-lite"));
        assertEquals("gemini-2.5-flash-lite",
                LlmClient.geminiModelId("google/gemini-2.5-flash-lite"));
    }

    @Test
    void costSafeModelClampsPremiumGeminiOverride() {
        String model = "google/gemini-2.5-";
        model = model + "pro";

        assertEquals("google/gemini-2.5-flash-lite", LlmClient.costSafeModel(model));
    }

    @Test
    void verifierRoleZeroesGeminiThinkingBudgetAndForcesJsonMime() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildGeminiBody("gemini-2.5-flash-lite", List.of(
                LlmClient.Message.system("verify this"),
                LlmClient.Message.userImages("see this", List.of(new byte[]{1, 2}))
        ), 0.0, 256, LlmClient.CallRole.VERIFIER);

        assertEquals(0,
                body.path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt(-1));
        assertEquals("application/json",
                body.path("generationConfig").path("responseMimeType").asText());
        assertEquals(256, body.path("generationConfig").path("maxOutputTokens").asInt());
    }

    @Test
    void verifierRoleSetsGemini3ThinkingLevelToNone() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildGeminiBody("gemini-3-fake", List.of(
                LlmClient.Message.user("verify")
        ), 0.0, 256, LlmClient.CallRole.VERIFIER);

        assertEquals("none",
                body.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());
        assertEquals("application/json",
                body.path("generationConfig").path("responseMimeType").asText());
    }

    @Test
    void binaryVerifierRolePinsGeminiResponseSchemaForOkReason() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildGeminiBody("gemini-2.5-flash-lite", List.of(
                LlmClient.Message.user("verify")
        ), 0.0, 256, LlmClient.CallRole.BINARY_VERIFIER);

        assertEquals("application/json",
                body.path("generationConfig").path("responseMimeType").asText());
        assertEquals(0,
                body.path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt(-1));
        var schema = body.path("generationConfig").path("responseSchema");
        assertEquals("OBJECT", schema.path("type").asText());
        assertEquals("BOOLEAN", schema.path("properties").path("ok").path("type").asText());
        assertEquals("STRING", schema.path("properties").path("reason").path("type").asText());
        var required = schema.path("required");
        assertTrue(required.isArray() && required.size() == 2,
                "responseSchema.required must list reason and ok");
        // Reason-first ordering: the model must commit reasoning before verdict.
        assertEquals("reason", required.get(0).asText());
        assertEquals("ok", required.get(1).asText());
        var ordering = schema.path("propertyOrdering");
        assertTrue(ordering.isArray() && ordering.size() == 2);
        assertEquals("reason", ordering.get(0).asText());
        assertEquals("ok", ordering.get(1).asText());
    }

    @Test
    void binaryVerifierRoleSetsOpenRouterJsonSchemaResponseFormat() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildBody("google/gemini-2.5-flash-lite",
                List.of(LlmClient.Message.user("verify")), 0.0, 256,
                LlmClient.CallRole.BINARY_VERIFIER);

        assertEquals("json_schema", body.path("response_format").path("type").asText());
        var schema = body.path("response_format").path("json_schema").path("schema");
        assertEquals("object", schema.path("type").asText());
        assertEquals("boolean", schema.path("properties").path("ok").path("type").asText());
        assertEquals("string", schema.path("properties").path("reason").path("type").asText());
    }

    @Test
    void verifierRoleSetsOpenRouterJsonResponseFormatAndOmitsReasoning() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildBody("google/gemini-2.5-flash-lite",
                List.of(LlmClient.Message.user("verify")), 0.0, 256, LlmClient.CallRole.VERIFIER);

        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertTrue(body.path("reasoning").isMissingNode() || body.path("reasoning").size() == 0);
    }

    @Test
    void parseReplyExtractsCachedAndReasoningTokens() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);
        String json = "{"
                + "\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{"
                + "\"prompt_tokens\":1500,"
                + "\"completion_tokens\":40,"
                + "\"total_tokens\":1540,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":1200},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":15}"
                + "}}";

        LlmClient.Reply reply = client.parseReply(json);

        assertEquals(1500, reply.promptTokens());
        assertEquals(1200, reply.cachedTokens());
        assertEquals(15, reply.reasoningTokens());
        assertEquals(40, reply.completionTokens());
    }

    @Test
    void parseGeminiReplyExtractsCachedContentTokenCount() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);
        String json = """
                {
                  "candidates": [{
                    "content": {"parts": [{"text": "ok"}]},
                    "finishReason": "STOP"
                  }],
                  "usageMetadata": {
                    "promptTokenCount": 800,
                    "candidatesTokenCount": 12,
                    "totalTokenCount": 812,
                    "cachedContentTokenCount": 700,
                    "thoughtsTokenCount": 22
                  }
                }
                """;

        LlmClient.Reply reply = client.parseGeminiReply(json);

        assertEquals(700, reply.cachedTokens());
        assertEquals(22, reply.reasoningTokens());
    }

    @Test
    void telemetryCallbackIsBindAbleAndUnbindable() {
        LlmClient.bindTelemetry(event -> {});
        LlmClient.unbindTelemetry();
        assertDoesNotThrow(() -> LlmClient.unbindTelemetry());
    }

    @Test
    void verifierRoleOmitsGlobalTraceContractFromGeminiSystemInstruction() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildGeminiBody("gemini-2.5-flash-lite", List.of(
                LlmClient.Message.system("verify only"),
                LlmClient.Message.user("does this prove the checkpoint?")
        ), 0.0, 256, LlmClient.CallRole.VERIFIER);

        String system = body.path("systemInstruction").path("parts").path(0).path("text").asText();
        assertFalse(system.contains("goal_link"),
                () -> "GLOBAL_TRACE_CONTRACT must not be injected for VERIFIER role: " + system);
        assertTrue(system.contains("verify only"));
    }

    @Test
    void verifierRoleOmitsGlobalTraceContractFromOpenRouterMessages() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildBody("openai/some-model", List.of(
                LlmClient.Message.user("verify only")
        ), 0.0, 256, LlmClient.CallRole.VERIFIER);

        for (JsonNode msg : body.path("messages")) {
            String content = msg.path("content").asText("");
            assertFalse(content.contains("goal_link"),
                    () -> "GLOBAL_TRACE_CONTRACT must not appear for VERIFIER role: " + content);
        }
    }

    @Test
    void plannerRoleStillInjectsGlobalTraceContract() {
        LlmClient client = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);

        ObjectNode body = client.buildBody("openai/some-model", List.of(
                LlmClient.Message.user("plan next action")
        ), 0.0, 256, LlmClient.CallRole.PLANNER);

        boolean found = false;
        for (JsonNode msg : body.path("messages")) {
            if (msg.path("content").asText("").contains("goal_link")) { found = true; break; }
        }
        assertTrue(found, "PLANNER role must still inject GLOBAL_TRACE_CONTRACT");
    }
}
