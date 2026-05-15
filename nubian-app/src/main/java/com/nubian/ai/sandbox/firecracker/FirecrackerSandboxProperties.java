package com.nubian.ai.sandbox.firecracker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nubian.sandbox.firecracker")
public class FirecrackerSandboxProperties {
    private boolean enabled = false;
    private String apiBaseUrl = "https://api.flyvm.io";
    private String apiKey = "";
    private String bearerToken = "";
    private String directNoVncUrl = "";
    private String staticVmId = "";
    private String jwtSecret = "";
    private String jwtIssuer = "flyvm-auth-service";
    private String jwtAudience = "flyvm-api";
    private String jwtTenantId = "";
    private String jwtTier = "enterprise";
    private long jwtQueryLimit = 1_000_000L;
    private Duration jwtTtl = Duration.ofHours(1);
    private String tenantPrefix = "agent-computer";
    private String serviceType = "computer";
    private String region = "";
    private String requiredProvider = "";
    private int memoryMib = 1024;
    private int vcpu = 1;
    private int dataDiskMib = 3072;
    private int guestNoVncPort = 6080;
    private int guestVncPort = 5900;
    private int guestAgentPort = 6090;
    private boolean repairNoVncProxy = false;
    private Duration provisionTimeout = Duration.ofSeconds(90);
    private Duration lifecycleTimeout = Duration.ofSeconds(45);
    private Duration commandTimeout = Duration.ofSeconds(120);
    private Duration guestHttpTimeout = Duration.ofSeconds(120);
    private Duration healthTimeout = Duration.ofSeconds(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = trimTrailingSlash(blankToDefault(apiBaseUrl, "https://api.flyvm.io"));
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
    }

    public String getDirectNoVncUrl() {
        return directNoVncUrl;
    }

    public void setDirectNoVncUrl(String directNoVncUrl) {
        this.directNoVncUrl = directNoVncUrl == null ? "" : directNoVncUrl.trim();
    }

    public String getStaticVmId() {
        return staticVmId;
    }

    public void setStaticVmId(String staticVmId) {
        this.staticVmId = staticVmId == null ? "" : staticVmId.trim();
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
    }

    public String getJwtIssuer() {
        return jwtIssuer;
    }

    public void setJwtIssuer(String jwtIssuer) {
        this.jwtIssuer = blankToDefault(jwtIssuer, "flyvm-auth-service");
    }

    public String getJwtAudience() {
        return jwtAudience;
    }

    public void setJwtAudience(String jwtAudience) {
        this.jwtAudience = blankToDefault(jwtAudience, "flyvm-api");
    }

    public String getJwtTenantId() {
        return jwtTenantId;
    }

    public void setJwtTenantId(String jwtTenantId) {
        this.jwtTenantId = jwtTenantId == null ? "" : jwtTenantId.trim();
    }

    public String getJwtTier() {
        return jwtTier;
    }

    public void setJwtTier(String jwtTier) {
        this.jwtTier = blankToDefault(jwtTier, "enterprise");
    }

    public long getJwtQueryLimit() {
        return jwtQueryLimit;
    }

    public void setJwtQueryLimit(long jwtQueryLimit) {
        this.jwtQueryLimit = Math.max(1L, jwtQueryLimit);
    }

    public Duration getJwtTtl() {
        return jwtTtl;
    }

    public void setJwtTtl(Duration jwtTtl) {
        this.jwtTtl = positiveDuration(jwtTtl, Duration.ofHours(1));
    }

    public String getTenantPrefix() {
        return tenantPrefix;
    }

    public void setTenantPrefix(String tenantPrefix) {
        this.tenantPrefix = blankToDefault(tenantPrefix, "agent-computer");
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = blankToDefault(serviceType, "computer");
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region == null ? "" : region.trim();
    }

    public String getRequiredProvider() {
        return requiredProvider;
    }

    public void setRequiredProvider(String requiredProvider) {
        this.requiredProvider = requiredProvider == null ? "" : requiredProvider.trim();
    }

    public int getMemoryMib() {
        return memoryMib;
    }

    public void setMemoryMib(int memoryMib) {
        this.memoryMib = Math.max(128, memoryMib);
    }

    public int getVcpu() {
        return vcpu;
    }

    public void setVcpu(int vcpu) {
        this.vcpu = Math.max(1, vcpu);
    }

    public int getDataDiskMib() {
        return dataDiskMib;
    }

    public void setDataDiskMib(int dataDiskMib) {
        this.dataDiskMib = Math.max(512, dataDiskMib);
    }

    public int getGuestNoVncPort() {
        return guestNoVncPort;
    }

    public void setGuestNoVncPort(int guestNoVncPort) {
        this.guestNoVncPort = validPort(guestNoVncPort, 6080);
    }

    public int getGuestVncPort() {
        return guestVncPort;
    }

    public void setGuestVncPort(int guestVncPort) {
        this.guestVncPort = validPort(guestVncPort, 5900);
    }

    public int getGuestAgentPort() {
        return guestAgentPort;
    }

    public void setGuestAgentPort(int guestAgentPort) {
        this.guestAgentPort = validPort(guestAgentPort, 6090);
    }

    public boolean isRepairNoVncProxy() {
        return repairNoVncProxy;
    }

    public void setRepairNoVncProxy(boolean repairNoVncProxy) {
        this.repairNoVncProxy = repairNoVncProxy;
    }

    public Duration getProvisionTimeout() {
        return provisionTimeout;
    }

    public void setProvisionTimeout(Duration provisionTimeout) {
        this.provisionTimeout = positiveDuration(provisionTimeout, Duration.ofSeconds(90));
    }

    public Duration getLifecycleTimeout() {
        return lifecycleTimeout;
    }

    public void setLifecycleTimeout(Duration lifecycleTimeout) {
        this.lifecycleTimeout = positiveDuration(lifecycleTimeout, Duration.ofSeconds(45));
    }

    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    public void setCommandTimeout(Duration commandTimeout) {
        this.commandTimeout = positiveDuration(commandTimeout, Duration.ofSeconds(120));
    }

    public Duration getGuestHttpTimeout() {
        return guestHttpTimeout;
    }

    public void setGuestHttpTimeout(Duration guestHttpTimeout) {
        this.guestHttpTimeout = positiveDuration(guestHttpTimeout, Duration.ofSeconds(120));
    }

    public Duration getHealthTimeout() {
        return healthTimeout;
    }

    public void setHealthTimeout(Duration healthTimeout) {
        this.healthTimeout = positiveDuration(healthTimeout, Duration.ofSeconds(15));
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static int validPort(int port, int defaultValue) {
        return port > 0 && port <= 65535 ? port : defaultValue;
    }

    private static Duration positiveDuration(Duration duration, Duration defaultValue) {
        return duration == null || duration.isZero() || duration.isNegative() ? defaultValue : duration;
    }
}
