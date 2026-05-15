package com.nubian.ai.sandbox.firecracker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

class ProcessBuilderFirecrackerCommandExecutor implements FirecrackerCommandExecutor {

    @Override
    public FirecrackerProcessResult execute(List<String> command, Duration timeout) {
        Instant startedAt = Instant.now();
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new FirecrackerProcessResult(
                        command,
                        -1,
                        read(process.getInputStream()),
                        read(process.getErrorStream()),
                        true,
                        true,
                        Duration.between(startedAt, Instant.now()));
            }
            return new FirecrackerProcessResult(
                    command,
                    process.exitValue(),
                    read(process.getInputStream()),
                    read(process.getErrorStream()),
                    true,
                    false,
                    Duration.between(startedAt, Instant.now()));
        } catch (IOException ex) {
            return new FirecrackerProcessResult(
                    command,
                    -1,
                    "",
                    ex.getMessage(),
                    false,
                    false,
                    Duration.between(startedAt, Instant.now()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new FirecrackerProcessResult(
                    command,
                    -1,
                    "",
                    ex.getMessage(),
                    true,
                    true,
                    Duration.between(startedAt, Instant.now()));
        }
    }

    private static String read(java.io.InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
