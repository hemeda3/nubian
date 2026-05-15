package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import com.nubian.ai.sandbox.model.SandboxFile;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async file-system API scoped to a sandbox session.
 */
public interface SandboxFileSystem extends SandboxCapability {

    @Override
    default SandboxCapabilityType type() {
        return SandboxCapabilityType.FILE_SYSTEM;
    }

    CompletableFuture<SandboxFile> readFile(String sessionId, String path);

    CompletableFuture<SandboxFile> writeFile(String sessionId, SandboxFile file);

    CompletableFuture<List<SandboxFile>> listFiles(String sessionId, String path);

    CompletableFuture<Void> createDirectory(String sessionId, String path);

    CompletableFuture<Void> deletePath(String sessionId, String path);
}
