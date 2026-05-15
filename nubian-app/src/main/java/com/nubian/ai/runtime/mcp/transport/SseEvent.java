package com.nubian.ai.runtime.mcp.transport;

/**
 * A single parsed Server-Sent Event (SSE) as defined by the W3C EventSource spec.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id}    — the {@code id:} field value, or {@code null} if absent</li>
 *   <li>{@code event} — the {@code event:} field value, or {@code "message"} if absent</li>
 *   <li>{@code data}  — all {@code data:} lines concatenated with {@code \n}, or {@code null}
 *                       if no data lines were present</li>
 *   <li>{@code retry} — the {@code retry:} field value in milliseconds, or {@code -1} if absent</li>
 * </ul>
 */
public record SseEvent(String id, String event, String data, long retry) {

    /** Sentinel value indicating the {@code retry} field was not present in the event. */
    public static final long NO_RETRY = -1L;

    /** Default event type when no {@code event:} line is present. */
    public static final String DEFAULT_EVENT = "message";

    public SseEvent {
        if (event == null) {
            event = DEFAULT_EVENT;
        }
    }

    /** Returns true if this event carries a non-null, non-empty data payload. */
    public boolean hasData() {
        return data != null && !data.isEmpty();
    }
}
