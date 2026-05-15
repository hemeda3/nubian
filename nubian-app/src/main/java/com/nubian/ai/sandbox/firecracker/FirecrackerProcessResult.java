package com.nubian.ai.sandbox.firecracker;

import java.time.Duration;
import java.util.List;

record FirecrackerProcessResult(
        List<String> command,
        int exitCode,
        String stdout,
        String stderr,
        boolean launched,
        boolean timedOut,
        Duration duration) {

    FirecrackerProcessResult {
        command = command == null ? List.of() : List.copyOf(command);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        duration = duration == null ? Duration.ZERO : duration;
    }

    boolean successfulExit() {
        return launched && !timedOut && exitCode == 0;
    }
}
