package com.nubian.ai.sandbox.computeragent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the computer-agent HTTP client.
 * Activated only when {@code nubian.sandbox.computer-agent.enabled=true}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "nubian.sandbox.computer-agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ComputerAgentProperties.class)
public class ComputerAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ComputerAgentEndpoints computerAgentEndpoints(ComputerAgentProperties props) {
        return props.toEndpoints();
    }

    @Bean
    @ConditionalOnMissingBean
    public ComputerAgentClient computerAgentClient(ComputerAgentEndpoints endpoints,
                                                    ObjectMapper mapper,
                                                    ComputerAgentProperties props) {
        return new ComputerAgentClient(endpoints, mapper, props.getRequestTimeout());
    }
}
