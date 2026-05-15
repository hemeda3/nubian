package com.nubian.ai.sandbox.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.sandbox.store.SandboxSessionStore;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.sqlite.JDBC")
@ConditionalOnProperty(prefix = "nubian.sandbox.persistence.sqlite", name = "enabled", havingValue = "true")
public class SqliteSandboxPersistenceConfiguration {

    @Bean
    @ConditionalOnMissingBean(SandboxSessionStore.class)
    public SandboxSessionStore sqliteSandboxSessionStore(
            ObjectMapper objectMapper,
            @Value("${nubian.sandbox.persistence.sqlite.path:${java.io.tmpdir}/nubian-sandbox.sqlite}") String path) {
        return new SqliteSandboxSessionStore(Path.of(path), objectMapper);
    }
}
