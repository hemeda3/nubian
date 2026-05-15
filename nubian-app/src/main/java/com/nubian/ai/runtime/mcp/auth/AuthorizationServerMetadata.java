package com.nubian.ai.runtime.mcp.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Authorization Server Metadata as defined by RFC 8414.
 *
 * <p>Discovered via the {@code /.well-known/oauth-authorization-server} or
 * {@code /.well-known/openid-configuration} endpoints with priority ordering
 * per the MCP 2025-11-25 specification.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthorizationServerMetadata(
        @JsonProperty("issuer") String issuer,
        @JsonProperty("authorization_endpoint") String authorizationEndpoint,
        @JsonProperty("token_endpoint") String tokenEndpoint,
        @JsonProperty("registration_endpoint") String registrationEndpoint,
        @JsonProperty("scopes_supported") List<String> scopesSupported,
        @JsonProperty("response_types_supported") List<String> responseTypesSupported,
        @JsonProperty("grant_types_supported") List<String> grantTypesSupported,
        @JsonProperty("code_challenge_methods_supported") List<String> codeChallengeMethodsSupported,
        @JsonProperty("client_id_metadata_document_supported") Boolean clientIdMetadataDocumentSupported,
        @JsonProperty("additional") Map<String, Object> additional) {
}
