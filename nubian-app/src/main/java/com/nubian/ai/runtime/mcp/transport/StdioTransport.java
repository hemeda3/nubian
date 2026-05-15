package com.nubian.ai.runtime.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.nubian.ai.runtime.mcp.protocol.JsonRpcMessage;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * MCP transport that communicates with a child process via its standard I/O streams
 * (JSON-RPC messages are newline-delimited on stdout/stdin).
 *
 * <p>The child process is started from the provided {@link ProcessBuilder} during
 * construction. Stderr output is forwarded to the SLF4J logger at INFO level with
 * a {@code [stdio-server]} prefix. The read loop runs on a daemon thread.
 *
 * <p>Usage:
 * <pre>{@code
 *   ProcessBuilder pb = new ProcessBuilder("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp");
 *   StdioTransport transport = new StdioTransport(pb);
 *   transport.startReadLoop();
 * }</pre>
 */
public class StdioTransport extends AbstractMcpTransport {

    private static final Logger logger = LoggerFactory.getLogger(StdioTransport.class);

    private final Process process;
    private final BufferedWriter stdin;
    private final Object writeLock = new Object();

    /**
     * Starts the child process described by {@code processBuilder} and wires up I/O.
     * {@link #startReadLoop()} must be called separately to begin reading responses.
     *
     * @param processBuilder configured builder for the MCP server process
     * @throws IOException if the process cannot be started
     */
    public StdioTransport(ProcessBuilder processBuilder) throws IOException {
        this.process = processBuilder.start();

        this.stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // Redirect stderr to a daemon thread that logs at INFO
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[stdio-server] {}", line);
                }
            } catch (IOException e) {
                if (!closed) {
                    logger.debug("[stdio-server] stderr stream closed: {}", e.getMessage());
                }
            }
        });
        stderrThread.setName("mcp-stdio-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    /**
     * Starts the daemon thread that reads newline-delimited JSON from the child
     * process stdout and dispatches each message via
     * {@link #dispatchIncoming(JsonRpcMessage)}.
     */
    @Override
    public void startReadLoop() {
        Thread readThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        JsonRpcMessage msg = McpJsonMapper.instance()
                                .readValue(line, JsonRpcMessage.class);
                        dispatchIncoming(msg);
                    } catch (JsonProcessingException e) {
                        logger.warn("Failed to parse JSON-RPC message from stdio: {} — line was: {}",
                                e.getMessage(), line);
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    logger.warn("stdio read loop I/O error: {}", e.getMessage());
                }
            }
            logger.debug("stdio read loop exited");
        });
        readThread.setName("mcp-stdio-reader");
        readThread.setDaemon(true);
        readThread.start();
    }

    /**
     * Writes a JSON string followed by a newline to the child process stdin.
     * Synchronized to prevent interleaving when multiple threads call send concurrently.
     */
    @Override
    protected void sendRaw(String json) throws IOException {
        synchronized (writeLock) {
            stdin.write(json);
            stdin.write('\n');
            stdin.flush();
        }
    }

    /**
     * Closes the transport:
     * <ol>
     *   <li>Calls {@code super.close()} to mark closed and fail pending futures.</li>
     *   <li>Closes the child process stdin.</li>
     *   <li>Waits up to 5 seconds for graceful exit.</li>
     *   <li>Calls {@code process.destroy()} if still running and waits another 5 seconds.</li>
     *   <li>Calls {@code process.destroyForcibly()} as a last resort.</li>
     * </ol>
     */
    @Override
    public void close() {
        super.close();

        // Close stdin to signal EOF to the child
        try {
            stdin.close();
        } catch (IOException e) {
            logger.debug("Error closing stdio stdin: {}", e.getMessage());
        }

        // Graceful wait
        try {
            boolean exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                logger.debug("stdio process did not exit gracefully, destroying");
                process.destroy();
                exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!exited) {
                    logger.debug("stdio process still alive after destroy, force-killing");
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
