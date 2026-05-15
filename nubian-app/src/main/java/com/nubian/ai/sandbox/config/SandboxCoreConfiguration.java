package com.nubian.ai.sandbox.config;

import com.nubian.ai.sandbox.lifecycle.SandboxStateMachine;
import com.nubian.ai.sandbox.policy.DefaultSandboxPolicyService;
import com.nubian.ai.sandbox.policy.SandboxPolicyService;
import com.nubian.ai.sandbox.registry.SandboxRegistry;
import com.nubian.ai.sandbox.store.InMemorySandboxSessionStore;
import com.nubian.ai.sandbox.store.SandboxSessionStore;
import com.nubian.ai.sandbox.synthesis.DefaultSandboxSynthesisService;
import com.nubian.ai.sandbox.synthesis.SandboxSynthesisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxCoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SandboxCoreConfiguration.class);
    private static final String API_PACKAGE = "com.nubian.ai.sandbox.api.";

    @Bean
    @ConditionalOnMissingBean(SandboxStateMachine.class)
    public SandboxStateMachine sandboxStateMachine() {
        return new SandboxStateMachine();
    }

    @Bean
    @ConditionalOnMissingBean(SandboxPolicyService.class)
    public SandboxPolicyService sandboxPolicyService(SandboxProperties properties) {
        return new DefaultSandboxPolicyService(properties);
    }

    @Bean
    @ConditionalOnMissingBean(SandboxSessionStore.class)
    public SandboxSessionStore sandboxSessionStore() {
        return new InMemorySandboxSessionStore();
    }

    @Bean
    @ConditionalOnMissingBean(SandboxSynthesisService.class)
    public SandboxSynthesisService sandboxSynthesisService(SandboxSessionStore sessionStore) {
        return new DefaultSandboxSynthesisService(sessionStore);
    }

    @Bean
    @ConditionalOnMissingBean(SandboxRegistry.class)
    public SandboxRegistry sandboxRegistry(
            SandboxProperties properties,
            ListableBeanFactory beanFactory) {
        boolean autoRegister = properties.getTools().isAutoRegister();
        return new SandboxRegistry(
                autoRegister ? beansOfApiType(beanFactory, "SandboxProvider") : List.of(),
                autoRegister ? beansOfApiType(beanFactory, "SandboxSessionService") : List.of(),
                autoRegister ? beansOfApiType(beanFactory, "SandboxFileSystem") : List.of(),
                autoRegister ? beansOfApiType(beanFactory, "SandboxTerminal") : List.of(),
                autoRegister ? beansOfApiType(beanFactory, "SandboxBrowser") : List.of(),
                autoRegister ? beansOfApiType(beanFactory, "SandboxDisplay") : List.of(),
                autoRegister ? beansOfApiType(beanFactory, "SandboxPorts") : List.of(),
                autoRegister ? beansOfApiType(beanFactory, "SandboxArtifacts") : List.of(),
                autoRegister ? beansOfApiType(beanFactory, "SandboxComputer") : List.of(),
                properties.getProvider());
    }

    private static List<?> beansOfApiType(ListableBeanFactory beanFactory, String simpleClassName) {
        try {
            Class<?> type = ClassUtils.forName(
                    API_PACKAGE + simpleClassName,
                    SandboxCoreConfiguration.class.getClassLoader());
            List<Object> beans = new ArrayList<>(beanFactory.getBeansOfType(type, false, false).values());
            AnnotationAwareOrderComparator.sort(beans);
            return List.copyOf(beans);
        } catch (ClassNotFoundException | LinkageError ex) {
            log.debug("beansOfApiType fallback: {}", ex.toString());
            return List.of();
        }
    }
}
