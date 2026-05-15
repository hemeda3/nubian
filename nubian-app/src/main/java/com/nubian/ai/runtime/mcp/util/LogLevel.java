package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * MCP logging severity levels, aligned with RFC 5424 syslog severities.
 *
 * <p>Per the MCP spec (2025-11-25) logging page: servers emit log messages at one of
 * these levels; clients may call {@code logging/setLevel} to filter. JSON serializes
 * to the lowercase name.
 */
public enum LogLevel {

    DEBUG,
    INFO,
    NOTICE,
    WARNING,
    ERROR,
    CRITICAL,
    ALERT,
    EMERGENCY;

    /** Serialize as lowercase JSON string per MCP spec. */
    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
