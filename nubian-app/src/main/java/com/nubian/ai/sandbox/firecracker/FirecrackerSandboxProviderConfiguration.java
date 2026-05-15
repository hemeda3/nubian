package com.nubian.ai.sandbox.firecracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nubian.sandbox.firecracker", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FirecrackerSandboxProperties.class)
public class FirecrackerSandboxProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FirecrackerSandboxProviderConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper firecrackerObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean(FlyVmComputerClient.class)
    FlyVmComputerClient flyVmComputerClient(
            FirecrackerSandboxProperties properties,
            ObjectMapper objectMapper) {
        return new FlyVmComputerClient(properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(FlyVmDiscoveryService.class)
    FlyVmDiscoveryService flyVmDiscoveryService(FlyVmComputerClient flyVmComputerClient) {
        return new FlyVmDiscoveryService(flyVmComputerClient);
    }

    @Bean
    @ConditionalOnMissingBean(FirecrackerSandboxSessionService.class)
    public FirecrackerSandboxSessionService firecrackerSandboxSessionService(
            FirecrackerSandboxProperties properties,
            FlyVmComputerClient flyVmComputerClient,
            FlyVmDiscoveryService discoveryService) {
        // Only run discovery when the firecracker provider is actually enabled and
        // the static-vm-id path is in use (i.e. directNoVncUrl is configured).
        // When provisioning dynamically, there is no static VM ID to validate.
        if (properties.isEnabled()
                && (hasText(properties.getStaticVmId())
                        || hasText(properties.getDirectNoVncUrl()))) {
            try {
                String effectiveVmId = discoveryService.validateOrDiscover(properties.getStaticVmId());
                if (!effectiveVmId.equals(properties.getStaticVmId())) {
                    log.info("[FlyVM] Updating effective VM ID from '{}' to '{}'",
                            properties.getStaticVmId(), effectiveVmId);
                    properties.setStaticVmId(effectiveVmId);
                }
            } catch (IllegalStateException ex) {
                // Re-throw to fail fast at boot with a clear actionable message
                throw ex;
            } catch (Exception ex) {
                // Unexpected error — log and continue; the agent loop will surface it
                log.warn("[FlyVM] VM ID discovery encountered an unexpected error: {}. "
                        + "Continuing with configured value '{}'.",
                        ex.getMessage(), properties.getStaticVmId());
            }
        }
        return new FirecrackerSandboxSessionService(properties, flyVmComputerClient);
    }

    @Bean
    @ConditionalOnMissingBean(FirecrackerSandboxProvider.class)
    public FirecrackerSandboxProvider firecrackerSandboxProvider(FirecrackerSandboxSessionService sessions) {
        return new FirecrackerSandboxProvider(sessions);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
