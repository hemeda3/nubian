package com.nubian.ai.runtime.mcp.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Parses a Server-Sent Events (SSE) stream per the W3C EventSource specification.
 *
 * <p>Parsing rules:
 * <ul>
 *   <li>Lines starting with {@code :} are comments and are ignored.</li>
 *   <li>An empty line dispatches the buffered event (if it has any data).</li>
 *   <li>{@code data:} lines are concatenated with {@code \n} between them.</li>
 *   <li>{@code event:} sets the event type; defaults to {@code "message"} if absent.</li>
 *   <li>{@code id:} sets the last-event-ID. An {@code id} with no value clears the ID.</li>
 *   <li>{@code retry:} sets the reconnection time if the value is all ASCII digits.</li>
 *   <li>A single leading space after the colon separator is stripped (per spec).</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 *   Flow.Publisher<SseEvent> pub = SseEventParser.parseStream(inputStream);
 *   pub.subscribe(new Flow.Subscriber<>() { ... });
 * }</pre>
 */
public final class SseEventParser {

    private static final Logger logger = LoggerFactory.getLogger(SseEventParser.class);

    private SseEventParser() {
    }

    /**
     * Returns a {@link Flow.Publisher} that emits one {@link SseEvent} per dispatched
     * SSE event from the given {@link InputStream}.
     *
     * <p>The stream is read on the calling thread of the first subscriber's
     * {@link Flow.Subscription#request(long)} call via a virtual-thread executor
     * inside {@link SubmissionPublisher}. The publisher is closed (and the stream
     * drained) when the input stream reaches EOF or throws.
     *
     * @param stream the raw SSE byte stream (e.g. from an HTTP response body)
     * @return a cold publisher; subscribe before the upstream connection is consumed
     */
    public static Flow.Publisher<SseEvent> parseStream(InputStream stream) {
        SubmissionPublisher<SseEvent> publisher = new SubmissionPublisher<>();

        Thread sseThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                parseInto(reader, publisher);
            } catch (IOException e) {
                logger.debug("SSE stream closed: {}", e.getMessage());
                publisher.closeExceptionally(e);
                return;
            }
            publisher.close();
        });
        sseThread.setName("sse-parser");
        sseThread.setDaemon(true);
        sseThread.start();

        return publisher;
    }

    /**
     * Blocking variant: reads from {@code reader} and submits parsed events to
     * {@code publisher}. Intended for callers that manage their own threading.
     * Returns when the reader reaches EOF.
     */
    static void parseInto(BufferedReader reader, SubmissionPublisher<SseEvent> publisher)
            throws IOException {
        // Per-event accumulators
        String id = null;
        String eventType = null;
        StringBuilder dataBuffer = null;
        long retry = SseEvent.NO_RETRY;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // Empty line — dispatch event
                if (dataBuffer != null) {
                    // Trim trailing \n that accumulation may have added
                    String data = dataBuffer.toString();
                    if (data.endsWith("\n")) {
                        data = data.substring(0, data.length() - 1);
                    }
                    SseEvent event = new SseEvent(id, eventType, data, retry);
                    publisher.submit(event);
                }
                // Reset accumulators (id persists across events per spec)
                eventType = null;
                dataBuffer = null;
                retry = SseEvent.NO_RETRY;
                continue;
            }

            if (line.startsWith(":")) {
                // Comment line — ignore
                continue;
            }

            String fieldName;
            String fieldValue;
            int colon = line.indexOf(':');
            if (colon == -1) {
                // Line with no colon: treat entire line as field name, empty value
                fieldName = line;
                fieldValue = "";
            } else {
                fieldName = line.substring(0, colon);
                // Strip exactly one leading space from value per spec
                String raw = line.substring(colon + 1);
                fieldValue = raw.startsWith(" ") ? raw.substring(1) : raw;
            }

            switch (fieldName) {
                case "data" -> {
                    if (dataBuffer == null) {
                        dataBuffer = new StringBuilder();
                    } else {
                        dataBuffer.append('\n');
                    }
                    dataBuffer.append(fieldValue);
                }
                case "event" -> eventType = fieldValue;
                case "id" -> {
                    // Empty id value clears the last-event-ID per spec
                    id = fieldValue.isEmpty() ? null : fieldValue;
                }
                case "retry" -> {
                    if (fieldValue.chars().allMatch(Character::isDigit)) {
                        try {
                            retry = Long.parseLong(fieldValue);
                        } catch (NumberFormatException e) {
                            logger.debug("SSE retry field out of range, ignoring: {}", fieldValue);
                        }
                    }
                }
                default -> logger.trace("SSE unknown field '{}', ignoring", fieldName);
            }
        }

        // If stream ended without a trailing empty line, dispatch buffered event
        if (dataBuffer != null) {
            String data = dataBuffer.toString();
            if (data.endsWith("\n")) {
                data = data.substring(0, data.length() - 1);
            }
            publisher.submit(new SseEvent(id, eventType, data, retry));
        }
    }
}
