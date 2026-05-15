package com.nubian.ai.runtime.mcp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * Discovery helpers for MCP OAuth 2.1 authorization.
 *
 * <p>Covers three discovery operations:
 * <ol>
 *   <li>{@link #fetchProtectedResourceMetadata(URI)} — fetches the RFC 9728
 *       protected resource metadata from a known URL.</li>
 *   <li>{@link #discoverFromMcpEndpoint(URI)} — probes for protected resource
 *       metadata starting from an MCP endpoint URI via well-known path probing
 *       (path-prefixed first, then root) per the MCP 2025-11-25 spec.</li>
 *   <li>{@link #fetchAuthorizationServerMetadata(String)} — fetches AS metadata
 *       using the priority order mandated by the spec:
 *       with path → {@code /.well-known/oauth-authorization-server/{PATH}} then
 *       {@code /.well-known/openid-configuration/{PATH}} then
 *       {@code {PATH}/.well-known/openid-configuration};
 *       without path → {@code /.well-known/oauth-authorization-server} then
 *       {@code /.well-known/openid-configuration}.</li>
 * </ol>
 */
public class McpAuthDiscovery {

    private static final Logger LOG = Logger.getLogger(McpAuthDiscovery.class.getName());

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final ObjectMapper mapper;

    /** Creates a discovery instance using the shared MCP mapper and a fresh HTTP/1.1 client. */
    public McpAuthDiscovery() {
        this(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build(),
            McpJsonMapper.instance());
    }

    /** Package-private constructor for testing with custom client/mapper. */
    McpAuthDiscovery(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Fetches Protected Resource Metadata from the given URL.
     *
     * @param resourceMetadataUrl absolute URL of the metadata document
     * @return the parsed {@link ProtectedResourceMetadata}
     * @throws McpAuthException if the fetch fails or returns non-200
     */
    public ProtectedResourceMetadata fetchProtectedResourceMetadata(URI resourceMetadataUrl) {
        String body = getJson(resourceMetadataUrl);
        try {
            return mapper.readValue(body, ProtectedResourceMetadata.class);
        } catch (IOException e) {
            throw new McpAuthException("Failed to parse ProtectedResourceMetadata from "
                    + resourceMetadataUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Discovers Protected Resource Metadata starting from an MCP endpoint URI.
     *
     * <p>Probe order per MCP 2025-11-25 spec (§Authorization / Discovery):
     * <ol>
     *   <li>Path-prefixed well-known: {@code {origin}/.well-known/oauth-protected-resource{path}}</li>
     *   <li>Root well-known: {@code {origin}/.well-known/oauth-protected-resource}</li>
     * </ol>
     *
     * @param mcpEndpoint the MCP server endpoint URI
     * @return the discovered {@link ProtectedResourceMetadata}
     * @throws McpAuthException if neither probe succeeds
     */
    public ProtectedResourceMetadata discoverFromMcpEndpoint(URI mcpEndpoint) {
        String origin = origin(mcpEndpoint);
        String path   = mcpEndpoint.getPath();

        // 1. Path-prefixed probe
        if (path != null && !path.isBlank() && !path.equals("/")) {
            String normalPath = path.startsWith("/") ? path : "/" + path;
            URI candidate = URI.create(origin + "/.well-known/oauth-protected-resource" + normalPath);
            try {
                return fetchProtectedResourceMetadata(candidate);
            } catch (McpAuthException e) {
                LOG.fine(() -> "Path-prefixed probe failed (" + candidate + "): " + e.getMessage());
            }
        }

        // 2. Root well-known probe
        URI root = URI.create(origin + "/.well-known/oauth-protected-resource");
        return fetchProtectedResourceMetadata(root);
    }

    /**
     * Fetches Authorization Server Metadata using the priority order in the MCP spec.
     *
     * <p>If the issuer URL has a non-trivial path component, the priority order is:
     * <ol>
     *   <li>{@code {origin}/.well-known/oauth-authorization-server{path}}</li>
     *   <li>{@code {origin}/.well-known/openid-configuration{path}}</li>
     *   <li>{@code {issuerUrl}/.well-known/openid-configuration} (path-appended)</li>
     * </ol>
     * If the issuer has no path (or path is {@code /}):
     * <ol>
     *   <li>{@code {origin}/.well-known/oauth-authorization-server}</li>
     *   <li>{@code {origin}/.well-known/openid-configuration}</li>
     * </ol>
     *
     * @param issuerUrl the issuer URL from the protected resource metadata
     * @return the parsed {@link AuthorizationServerMetadata}
     * @throws McpAuthException if all probes fail
     */
    public AuthorizationServerMetadata fetchAuthorizationServerMetadata(String issuerUrl) {
        URI issuerUri = URI.create(issuerUrl.endsWith("/")
                ? issuerUrl.substring(0, issuerUrl.length() - 1)
                : issuerUrl);
        String origin = origin(issuerUri);
        String path   = issuerUri.getPath();
        boolean hasPath = path != null && !path.isBlank() && !path.equals("/");

        List<String> candidates;
        if (hasPath) {
            String normalPath = path.startsWith("/") ? path : "/" + path;
            candidates = List.of(
                origin + "/.well-known/oauth-authorization-server" + normalPath,
                origin + "/.well-known/openid-configuration" + normalPath,
                issuerUri.toASCIIString() + "/.well-known/openid-configuration"
            );
        } else {
            candidates = List.of(
                origin + "/.well-known/oauth-authorization-server",
                origin + "/.well-known/openid-configuration"
            );
        }

        McpAuthException last = null;
        for (String url : candidates) {
            try {
                String body = getJson(URI.create(url));
                return mapper.readValue(body, AuthorizationServerMetadata.class);
            } catch (McpAuthException e) {
                LOG.fine(() -> "AS metadata probe failed (" + url + "): " + e.getMessage());
                last = e;
            } catch (IOException e) {
                LOG.fine(() -> "AS metadata parse failed (" + url + "): " + e.getMessage());
                last = new McpAuthException("Parse error at " + url + ": " + e.getMessage(), e);
            }
        }

        throw new McpAuthException("All AS metadata probes failed for issuer: " + issuerUrl, last);
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private String getJson(URI uri) {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new McpAuthException("HTTP error fetching " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpAuthException("Request interrupted fetching " + uri, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new McpAuthException(
                    "HTTP " + resp.statusCode() + " fetching " + uri);
        }
        return resp.body();
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "https";
        String host   = uri.getHost()   != null ? uri.getHost().toLowerCase()   : "";
        return port > 0
                ? scheme + "://" + host + ":" + port
                : scheme + "://" + host;
    }
}
