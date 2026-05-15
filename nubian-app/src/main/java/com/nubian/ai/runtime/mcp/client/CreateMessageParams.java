package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.nubian.ai.runtime.mcp.tools.ToolDefinition;

import java.util.List;

/**
 * Parameters for a {@code sampling/createMessage} request sent by the server.
 *
 * <p>Per the MCP spec (2025-11-25): the server asks the client to perform an LLM call
 * on its behalf. The client routes to its configured model backend, optionally with
 * human-in-the-loop review, and returns the result.
 *
 * @param messages         The conversation history to send to the model (required).
 * @param modelPreferences Optional guidance on which model to select.
 * @param systemPrompt     Optional system prompt to prepend.
 * @param includeContext   Soft-deprecated: whether to include MCP context automatically.
 * @param temperature      Optional sampling temperature.
 * @param maxTokens        Maximum number of tokens to generate (required, must be &gt; 0).
 * @param stopSequences    Optional list of stop sequence strings.
 * @param metadata         Optional arbitrary metadata passed through to the LLM call.
 * @param tools            Optional list of tools available to the model.
 * @param toolChoice       Optional instruction on tool-call behaviour.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateMessageParams(
        @JsonProperty("messages") List<SamplingMessage> messages,
        @JsonProperty("modelPreferences") ModelPreferences modelPreferences,
        @JsonProperty("systemPrompt") String systemPrompt,
        @JsonProperty("includeContext") IncludeContext includeContext,
        @JsonProperty("temperature") Double temperature,
        @JsonProperty("maxTokens") int maxTokens,
        @JsonProperty("stopSequences") List<String> stopSequences,
        @JsonProperty("metadata") JsonNode metadata,
        @JsonProperty("tools") List<ToolDefinition> tools,
        @JsonProperty("toolChoice") ToolChoice toolChoice) {

    public CreateMessageParams {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException(
                    "CreateMessageParams.messages must not be null or empty");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException(
                    "CreateMessageParams.maxTokens must be > 0 but was: " + maxTokens);
        }
    }
}
