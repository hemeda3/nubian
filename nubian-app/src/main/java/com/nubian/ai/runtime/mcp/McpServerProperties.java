package com.nubian.ai.runtime.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot configuration properties for MCP server connections.
 *
 * <p>Bind under the prefix {@code nubian.mcp}. Example YAML:
 * <pre>{@code
 * nubian:
 *   mcp:
 *     auto-start: true
 *     servers:
 *       - name: filesystem
 *         type: stdio
 *         command: npx
 *         args: ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
 *       - name: remote
 *         type: http
 *         url: https://mcp.example.com/mcp
 *         auth:
 *           resource: https://mcp.example.com
 *           issuer: https://auth.example.com
 *           clientId: my-client
 *           clientSecret: secret
 * }</pre>
 */
@ConfigurationProperties(prefix = "nubian.mcp")
public class McpServerProperties {

    /** Whether to auto-start configured MCP servers at application startup. */
    private boolean autoStart = false;

    /** List of MCP server connection specifications. */
    private List<ServerSpec> servers = new ArrayList<>();

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public List<ServerSpec> getServers() {
        return servers;
    }

    public void setServers(List<ServerSpec> servers) {
        this.servers = servers != null ? servers : new ArrayList<>();
    }

    // -----------------------------------------------------------------------
    // Nested types
    // -----------------------------------------------------------------------

    /**
     * Specification for a single MCP server connection.
     *
     * @param name    Unique logical name for this server in the registry.
     * @param type    Transport type: {@code "stdio"} or {@code "http"}.
     * @param command Executable to launch (stdio only).
     * @param args    Arguments for the executable (stdio only).
     * @param url     MCP endpoint URL (http only).
     * @param env     Additional environment variables injected into the stdio process.
     * @param auth    OAuth/bearer authentication spec (http only).
     */
    public record ServerSpec(
            String name,
            String type,
            String command,
            List<String> args,
            String url,
            Map<String, String> env,
            AuthSpec auth
    ) {
        public ServerSpec {
            args = args != null ? args : List.of();
            env  = env  != null ? env  : Map.of();
        }
    }

    /**
     * OAuth 2.0 / bearer authentication configuration for an HTTP MCP server.
     *
     * @param resource      The protected resource URI (RFC 8707).
     * @param issuer        OIDC/OAuth issuer URL.
     * @param clientId      OAuth client ID.
     * @param clientSecret  OAuth client secret.
     * @param redirectUris  Allowed redirect URIs for the OAuth flow.
     * @param scopes        Requested OAuth scopes.
     */
    public record AuthSpec(
            String resource,
            String issuer,
            String clientId,
            String clientSecret,
            List<String> redirectUris,
            List<String> scopes
    ) {
        public AuthSpec {
            redirectUris = redirectUris != null ? redirectUris : List.of();
            scopes       = scopes       != null ? scopes       : List.of();
        }
    }
}
