package com.nubian.ai.runtime.mcp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches and validates a Client ID Metadata Document.
 *
 * <p>Per the Client ID Metadata Document draft (referenced by MCP 2025-11-25),
 * the OAuth {@code client_id} IS the HTTPS URL of this document. The document
 * must be retrievable from that URL and must contain a {@code client_id} field
 * that exactly matches the URL used to fetch it.
 *
 * <p>This class also validates:
 * <ul>
 *   <li>The URL scheme is {@code https} (document URLs must not use plain http).</li>
 *   <li>The response contains a valid {@code client_id} field.</li>
 *   <li>The {@code client_id} in the document equals the fetch URL exactly.</li>
 * </ul>
 */
public class ClientIdMetadataDocumentClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final ObjectMapper mapper;

    /** Creates a client using the shared MCP mapper and a fresh HTTP/1.1 client. */
    public ClientIdMetadataDocumentClient() {
        this(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build(),
            McpJsonMapper.instance());
    }

    /** Package-private constructor for testing. */
    ClientIdMetadataDocumentClient(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * Fetches the client metadata document at {@code metadataUrl}, validates its
     * structure, and confirms that the embedded {@code client_id} matches the URL.
     *
     * @param metadataUrl the HTTPS URL that serves as the {@code client_id}
     * @return the raw {@link JsonNode} document for callers that need additional fields
     * @throws McpAuthException if the URL is not HTTPS, the fetch fails, the document
     *                          structure is invalid, or the {@code client_id} does not match
     */
    public JsonNode fetch(String metadataUrl) {
        URI uri = URI.create(metadataUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new McpAuthException(
                    "Client ID Metadata Document URL must use HTTPS: " + metadataUrl);
        }

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new McpAuthException(
                    "Failed to fetch client metadata document from " + metadataUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpAuthException("Request interrupted fetching " + metadataUrl, e);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new McpAuthException(
                    "HTTP " + resp.statusCode() + " fetching client metadata document: " + metadataUrl);
        }

        JsonNode doc;
        try {
            doc = mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new McpAuthException(
                    "Invalid JSON in client metadata document at " + metadataUrl + ": " + e.getMessage(), e);
        }

        // Validate structure
        if (!doc.isObject()) {
            throw new McpAuthException("Client metadata document must be a JSON object: " + metadataUrl);
        }
        JsonNode clientIdNode = doc.get("client_id");
        if (clientIdNode == null || clientIdNode.isNull() || !clientIdNode.isTextual()) {
            throw new McpAuthException(
                    "Client metadata document missing 'client_id' field at: " + metadataUrl);
        }

        // The client_id MUST exactly match the URL used to fetch the document
        String embeddedClientId = clientIdNode.asText();
        if (!metadataUrl.equals(embeddedClientId)) {
            throw new McpAuthException(
                    "client_id in document ('" + embeddedClientId
                    + "') does not match fetch URL ('" + metadataUrl + "')");
        }

        return doc;
    }
}
