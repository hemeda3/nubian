package com.nubian.ai.runtime.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * MCP progress token.
 *
 * <p>Per the MCP spec (2025-11-25): "A progress token, used to associate progress
 * notifications with the original request. MUST be a string or integer value."
 *
 * <p>Two permitted variants:
 * <ul>
 *   <li>{@link StringToken} — string-valued progress token</li>
 *   <li>{@link LongToken} — integer-valued progress token</li>
 * </ul>
 */
public sealed interface ProgressToken permits ProgressToken.StringToken, ProgressToken.LongToken {

    /** Return the raw value (either {@link String} or {@link Long}). */
    @JsonValue
    Object value();

    /**
     * Deserialize from JSON: strings map to {@link StringToken}, integers to {@link LongToken}.
     */
    @JsonCreator
    static ProgressToken fromJson(Object raw) {
        if (raw instanceof String s) {
            return new StringToken(s);
        }
        if (raw instanceof Integer i) {
            return new LongToken(i.longValue());
        }
        if (raw instanceof Long l) {
            return new LongToken(l);
        }
        if (raw instanceof Number n) {
            return new LongToken(n.longValue());
        }
        throw new IllegalArgumentException(
                "ProgressToken must be a string or integer, got: " + (raw == null ? "null" : raw.getClass().getName()));
    }

    /** String-valued progress token. */
    record StringToken(String value) implements ProgressToken {
        public StringToken {
            if (value == null) {
                throw new IllegalArgumentException("ProgressToken.StringToken value must not be null");
            }
        }
    }

    /** Integer-valued progress token (stored as {@code long}). */
    record LongToken(Long value) implements ProgressToken {
        public LongToken {
            if (value == null) {
                throw new IllegalArgumentException("ProgressToken.LongToken value must not be null");
            }
        }

        /** Convenience constructor from primitive {@code long}. */
        LongToken(long value) {
            this(Long.valueOf(value));
        }
    }

    /** Factory helper — accepts {@code String} or {@code Long}/{@code Integer}. */
    static ProgressToken of(Object value) {
        return fromJson(value);
    }
}
