package com.nubian.ai.sandbox.firecracker;

import java.time.Duration;
import java.util.List;

interface FirecrackerCommandExecutor {
    FirecrackerProcessResult execute(List<String> command, Duration timeout);
}
