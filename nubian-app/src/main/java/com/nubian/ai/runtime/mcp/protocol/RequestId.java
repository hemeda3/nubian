package com.nubian.ai.runtime.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * JSON-RPC 2.0 request identifier.
 *
 * <p>Per the MCP spec (2025-11-25): "An identifier established by the Client that
 * MUST contain a String, Number, or NULL value if included. If it is not included
 * it is assumed to be a notification. The value SHOULD normally not be Null and
 * Numbers SHOULD NOT contain fractional parts."
 *
 * <p>The spec further states the ID MUST NOT be null in requests — {@code null} is
 * rejected at construction time.
 *
 * <p>Only two concrete variants are permitted:
 * <ul>
 *   <li>{@link StringId} — wraps a {@code String} identifier</li>
 *   <li>{@link LongId} — wraps a {@code long} / {@code Long} integer identifier</li>
 * </ul>
 */
public sealed interface RequestId permits RequestId.StringId, RequestId.LongId {

    /** Return the raw value (either {@link String} or {@link Long}). */
    @JsonValue
    Object value();

    /**
     * Deserialize from JSON: strings map to {@link StringId}, integers to {@link LongId}.
     * Jackson calls this when the target type is {@code RequestId}.
     */
    @JsonCreator
    static RequestId fromJson(Object raw) {
        if (raw instanceof String s) {
            return new StringId(s);
        }
        if (raw instanceof Integer i) {
            return new LongId(i.longValue());
        }
        if (raw instanceof Long l) {
            return new LongId(l);
        }
        if (raw instanceof Number n) {
            return new LongId(n.longValue());
        }
        throw new IllegalArgumentException(
                "RequestId must be a string or integer, got: " + (raw == null ? "null" : raw.getClass().getName()));
    }

    /** String-valued request ID. */
    record StringId(String value) implements RequestId {
        public StringId {
            if (value == null) {
                throw new IllegalArgumentException("RequestId.StringId value must not be null");
            }
        }
    }

    /** Integer-valued request ID (stored as {@code long}). */
    record LongId(Long value) implements RequestId {
        public LongId {
            if (value == null) {
                throw new IllegalArgumentException("RequestId.LongId value must not be null");
            }
        }

        /** Convenience constructor from primitive {@code long}. */
        LongId(long value) {
            this(Long.valueOf(value));
        }
    }

    /** Factory helper — accepts {@code String} or {@code Long}/{@code Integer}. */
    static RequestId of(Object value) {
        return fromJson(value);
    }
}
