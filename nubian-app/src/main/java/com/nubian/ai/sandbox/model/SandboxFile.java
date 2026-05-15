package com.nubian.ai.sandbox.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * File metadata plus optional in-memory content.
 */
public record SandboxFile(
        String path,
        boolean directory,
        long sizeBytes,
        Instant modifiedAt,
        String mediaType,
        byte[] content,
        Map<String, String> metadata
) {
    public SandboxFile {
        path = Objects.requireNonNull(path, "path");
        mediaType = Objects.requireNonNullElse(mediaType, "application/octet-stream");
        content = content == null ? new byte[0] : content.clone();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
