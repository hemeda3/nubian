package com.nubian.ai.runtime.mcp.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Protected Resource Metadata as defined by RFC 9728.
 *
 * <p>Describes the resource server's identity and the authorization servers that
 * protect it. Discovered via the well-known URI
 * {@code /.well-known/oauth-protected-resource} (path-prefixed per spec).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProtectedResourceMetadata(
        @JsonProperty("resource") String resource,
        @JsonProperty("authorization_servers") List<String> authorizationServers,
        @JsonProperty("scopes_supported") List<String> scopesSupported,
        @JsonProperty("bearer_methods_supported") List<String> bearerMethodsSupported,
        @JsonProperty("additional") Map<String, Object> additional) {
}
