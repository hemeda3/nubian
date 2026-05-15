package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Params payload carried inside a {@code notifications/resources/updated} notification.
 *
 * <p>When the server sends this notification, clients subscribed to the given URI
 * should re-fetch the resource via {@code resources/read}.
 *
 * @param uri Required. The URI of the resource that changed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceUpdatedNotification(String uri) {
    public ResourceUpdatedNotification {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("ResourceUpdatedNotification.uri must not be blank");
        }
    }
}
