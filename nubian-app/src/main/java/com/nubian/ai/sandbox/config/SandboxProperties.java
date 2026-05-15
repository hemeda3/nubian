package com.nubian.ai.sandbox.config;

import com.nubian.ai.sandbox.policy.SandboxOperation;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nubian.sandbox")
public class SandboxProperties {

    private String provider;
    private final Tools tools = new Tools();
    private final Security security = new Security();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Tools getTools() {
        return tools;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Tools {

        private boolean autoRegister = true;

        public boolean isAutoRegister() {
            return autoRegister;
        }

        public void setAutoRegister(boolean autoRegister) {
            this.autoRegister = autoRegister;
        }
    }

    public static class Security {

        private boolean enabled = true;
        private boolean allowPrivilegedDocker = false;
        private Set<String> allowedDockerImages = new LinkedHashSet<>();
        private Set<SandboxOperation> deniedOperations = new LinkedHashSet<>();
        private Set<String> deniedPathPrefixes = new LinkedHashSet<>();
        private Set<String> deniedEnvironmentKeys = new LinkedHashSet<>();
        private Set<String> deniedCommands = new LinkedHashSet<>(Set.of(
                "rm -rf /",
                "rm -fr /",
                "mkfs",
                "dd if=",
                ":(){:|:&};:"));
        private boolean allowPublicPorts = true;
        private long maxCommandTimeoutSeconds = 0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAllowPrivilegedDocker() {
            return allowPrivilegedDocker;
        }

        public void setAllowPrivilegedDocker(boolean allowPrivilegedDocker) {
            this.allowPrivilegedDocker = allowPrivilegedDocker;
        }

        public Set<String> getAllowedDockerImages() {
            return allowedDockerImages;
        }

        public void setAllowedDockerImages(Set<String> allowedDockerImages) {
            this.allowedDockerImages = allowedDockerImages == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(allowedDockerImages);
        }

        public Set<String> getDeniedCommands() {
            return deniedCommands;
        }

        public void setDeniedCommands(Set<String> deniedCommands) {
            this.deniedCommands = deniedCommands == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(deniedCommands);
        }

        public Set<SandboxOperation> getDeniedOperations() {
            return deniedOperations;
        }

        public void setDeniedOperations(Set<SandboxOperation> deniedOperations) {
            this.deniedOperations = deniedOperations == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(deniedOperations);
        }

        public Set<String> getDeniedPathPrefixes() {
            return deniedPathPrefixes;
        }

        public void setDeniedPathPrefixes(Set<String> deniedPathPrefixes) {
            this.deniedPathPrefixes = deniedPathPrefixes == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(deniedPathPrefixes);
        }

        public Set<String> getDeniedEnvironmentKeys() {
            return deniedEnvironmentKeys;
        }

        public void setDeniedEnvironmentKeys(Set<String> deniedEnvironmentKeys) {
            this.deniedEnvironmentKeys = deniedEnvironmentKeys == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(deniedEnvironmentKeys);
        }

        public boolean isAllowPublicPorts() {
            return allowPublicPorts;
        }

        public void setAllowPublicPorts(boolean allowPublicPorts) {
            this.allowPublicPorts = allowPublicPorts;
        }

        public long getMaxCommandTimeoutSeconds() {
            return maxCommandTimeoutSeconds;
        }

        public void setMaxCommandTimeoutSeconds(long maxCommandTimeoutSeconds) {
            this.maxCommandTimeoutSeconds = Math.max(0, maxCommandTimeoutSeconds);
        }
    }
}
