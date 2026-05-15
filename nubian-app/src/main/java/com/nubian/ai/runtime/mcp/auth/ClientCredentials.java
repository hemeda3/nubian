package com.nubian.ai.runtime.mcp.auth;

import java.util.List;

/**
 * Sealed interface representing the three ways an MCP client can identify itself
 * to an authorization server.
 *
 * <ul>
 *   <li>{@link PreRegisteredClient} — static client_id/secret configured out of band.</li>
 *   <li>{@link ClientIdMetadataDocument} — client_id IS the HTTPS URL of a metadata
 *       document (per the Client ID Metadata Document draft). No secret is used.</li>
 *   <li>{@link DynamicallyRegisteredClient} — credentials obtained at runtime via
 *       RFC 7591 Dynamic Client Registration at the AS {@code /register} endpoint.</li>
 * </ul>
 */
public sealed interface ClientCredentials
        permits ClientCredentials.PreRegisteredClient,
                ClientCredentials.ClientIdMetadataDocument,
                ClientCredentials.DynamicallyRegisteredClient {

    /** Returns the client identifier sent to the authorization server. */
    String clientId();

    /** Returns the ordered list of redirect URIs registered for this client. */
    List<String> redirectUris();

    // ------------------------------------------------------------------
    // Variants
    // ------------------------------------------------------------------

    /**
     * Statically pre-registered client with an optional shared secret.
     * Public clients (SPAs, CLIs) set {@code clientSecret} to {@code null}.
     */
    record PreRegisteredClient(
            String clientId,
            String clientSecret,
            List<String> redirectUris) implements ClientCredentials {
    }

    /**
     * Client whose {@code client_id} is the HTTPS URL of its metadata document.
     * The URL itself is sent as {@code client_id}; no secret is required.
     */
    record ClientIdMetadataDocument(
            String metadataUrl,
            List<String> redirectUris) implements ClientCredentials {

        /** The client_id IS the metadata URL per the draft spec. */
        @Override
        public String clientId() {
            return metadataUrl;
        }
    }

    /**
     * Client credentials obtained via RFC 7591 Dynamic Client Registration.
     * Populated after a successful {@code POST /register} call.
     */
    record DynamicallyRegisteredClient(
            String clientId,
            String clientSecret,
            List<String> redirectUris) implements ClientCredentials {
    }
}
