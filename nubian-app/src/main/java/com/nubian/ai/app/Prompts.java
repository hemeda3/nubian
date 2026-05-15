package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component("appPrompts")
public final class Prompts {

    private static final Logger log = LoggerFactory.getLogger(Prompts.class);
    private static final String RESOURCE = "app-prompts.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;

    public Prompts() {
        JsonNode loaded;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            loaded = MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            log.info("[prompts] loaded {} bytes from classpath:{}", root_size(loaded), RESOURCE);
        } catch (Exception ex) {
            log.warn("[prompts] failed to load {}: {} — using empty defaults", RESOURCE, ex.toString());
            loaded = MAPPER.createObjectNode();
        }
        this.root = loaded;
    }

    public String getResource(String resourceName) {
        return loadResource(resourceName);
    }

    private static String loadResource(String name) {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("[prompts] failed to load classpath:{}: {}", name, ex.toString());
            return "";
        }
    }

    private static int root_size(JsonNode n) {
        try { return MAPPER.writeValueAsBytes(n).length; } catch (Exception ex) { return -1; }
    }

    public String getString(String dottedPath) {
        JsonNode node = lookup(dottedPath);
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) b.append('\n');
                b.append(node.get(i).asText());
            }
            return b.toString();
        }
        return node.toString();
    }

    public String getString(String dottedPath, String fallback) {
        String v = getString(dottedPath);
        return v == null ? fallback : v;
    }

    public List<String> getLines(String dottedPath) {
        JsonNode node = lookup(dottedPath);
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) out.add(node.get(i).asText());
        return out;
    }

    private JsonNode lookup(String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) return null;
        JsonNode cur = root;
        for (String seg : dottedPath.split("\\.")) {
            if (cur == null) return null;
            cur = cur.get(seg);
        }
        return cur;
    }
}
