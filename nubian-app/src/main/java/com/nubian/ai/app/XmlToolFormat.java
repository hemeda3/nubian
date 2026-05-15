package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XmlToolFormat {

    private static final Pattern TOOLS = Pattern.compile(
            "<tools>(.*?)</tools>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TOOL = Pattern.compile(
            "<tool\\s+name\\s*=\\s*[\"']([^\"']+)[\"']\\s*>(.*?)</tool>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern REASON = Pattern.compile(
            "<reason\\s*>(.*?)</reason\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ARGS = Pattern.compile(
            "<args\\s*>(.*?)(?:</args\\s*>|(?=</tool\\s*>)|(?=<tool\\b)|(?=<code\\b)|\\z)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile(
            "<code(\\s+[^>]*)?>(.*?)</code\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMEOUT_ATTR = Pattern.compile(
            "timeout_ms\\s*=\\s*[\"']?(\\d+)[\"']?",
            Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private XmlToolFormat() {}

    public record ParsedCall(String name, String argsJson, String reason) {}

    public static List<ParsedCall> parseToolCalls(String responseText) {
        if (responseText == null || responseText.isEmpty()) return List.of();
        Matcher blockMatcher = TOOLS.matcher(responseText);
        String lastBlock = null;
        while (blockMatcher.find()) lastBlock = blockMatcher.group(1);
        if (lastBlock == null) {
            int open = indexOfIgnoreCase(responseText, "<tools>");
            if (open < 0) return List.of();
            lastBlock = responseText.substring(open + "<tools>".length());
        }

        List<ParsedCall> out = new ArrayList<>();
        Matcher tools = TOOL.matcher(lastBlock);
        while (tools.find()) {
            String name = tools.group(1).trim();
            String inner = tools.group(2);
            String reason = firstGroup(REASON.matcher(inner));
            String argsJson;
            Matcher codeMatcher = CODE.matcher(inner);
            if (codeMatcher.find()) {
                String attrs = codeMatcher.group(1) == null ? "" : codeMatcher.group(1);
                String body = codeMatcher.group(2);
                argsJson = buildCodeArgs(body, attrs, firstGroup(ARGS.matcher(inner)));
            } else {
                argsJson = normalizeJson(firstGroup(ARGS.matcher(inner)));
            }
            out.add(new ParsedCall(name, argsJson, reason == null ? "" : reason.trim()));
        }
        return out;
    }

    private static String buildCodeArgs(String codeBody, String codeAttrs, String argsBody) {
        ObjectNode obj = MAPPER.createObjectNode();
        String body = codeBody == null ? "" : codeBody;
        body = body.replaceFirst("^\\s*<!\\[CDATA\\[", "").replaceFirst("\\]\\]>\\s*$", "");
        if (body.startsWith("\n")) body = body.substring(1);
        else if (body.startsWith("\r\n")) body = body.substring(2);
        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        obj.put("code", body);
        Matcher tm = TIMEOUT_ATTR.matcher(codeAttrs == null ? "" : codeAttrs);
        if (tm.find()) {
            try { obj.put("timeout_ms", Long.parseLong(tm.group(1))); } catch (NumberFormatException ignore) {}
        }
        if (argsBody != null && !argsBody.isBlank()) {
            try {
                JsonNode extra = MAPPER.readTree(argsBody.trim());
                if (extra.isObject()) {
                    extra.fieldNames().forEachRemaining(k -> {
                        if (!"code".equals(k) && !obj.has(k)) obj.set(k, extra.get(k));
                    });
                }
            } catch (Exception ignore) {}
        }
        try { return MAPPER.writeValueAsString(obj); } catch (Exception ex) { return "{}"; }
    }

    public static String stripToolsBlock(String text) {
        if (text == null || text.isEmpty()) return text;
        String stripped = text.replaceAll("(?is)<tools>.*?</tools>", "").trim();
        return stripped.isEmpty() ? text.trim() : stripped;
    }

    private static String firstGroup(Matcher m) {
        return m.find() ? m.group(1) : null;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        if (text == null || needle == null) return -1;
        if (needle.isEmpty()) return 0;
        int max = text.length() - needle.length();
        for (int i = 0; i <= max; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static String normalizeJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.isEmpty()) return "{}";
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) s = s.substring(firstNl + 1);
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) s = s.substring(0, lastFence);
            s = s.trim();
        }
        try {
            JsonNode node = MAPPER.readTree(s);
            return MAPPER.writeValueAsString(node);
        } catch (Exception ignore) {
            try {
                Map<String, Object> kv = new java.util.LinkedHashMap<>();
                for (String tok : s.split("[,\\n]+")) {
                    int eq = tok.indexOf('=');
                    int co = tok.indexOf(':');
                    int sep = (eq >= 0 && (co < 0 || eq < co)) ? eq : co;
                    if (sep <= 0) continue;
                    String key = tok.substring(0, sep).trim().replace("\"", "");
                    String val = tok.substring(sep + 1).trim();
                    if (key.isEmpty()) continue;
                    if (val.matches("-?\\d+")) kv.put(key, Long.parseLong(val));
                    else if (val.matches("-?\\d+\\.\\d+")) kv.put(key, Double.parseDouble(val));
                    else kv.put(key, val);
                }
                return MAPPER.writeValueAsString(kv);
            } catch (Exception ex2) {
                return "{}";
            }
        }
    }
}
