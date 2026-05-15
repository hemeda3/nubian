package com.nubian.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.nubian.ai.postgrest.SupabaseClient;
import com.nubian.ai.postgrest.SupabaseDdlClient;

/**
 * Configuration for Supabase connection.
 * 
 * This class provides the necessary beans for connecting to Supabase,
 * including the Supabase client configured with the URL and service role key.
 */
@Configuration
@ConditionalOnProperty(prefix = "nubian.supabase", name = "enabled", havingValue = "true")
public class SupabaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(SupabaseConfig.class);
    
    @Value("${SUPABASE_URL}")
    private String supabaseUrl;
    
    @Value("${SUPABASE_SERVICE_ROLE_KEY}")
    private String supabaseServiceRoleKey;
    
    @Value("${SUPABASE_SCHEMA:public}")
    private String supabaseSchema;
    
    /**
     * Create a standard Supabase client bean.
     * 
     * @return The Supabase client
     */
    @Bean
    public SupabaseClient supabaseClient() {
        logger.info("Initializing Supabase client with URL: {}, Schema: {}", supabaseUrl, supabaseSchema);
        
        SupabaseClient client = new SupabaseClient(
                supabaseUrl,
                supabaseServiceRoleKey,
                supabaseSchema);
        
        logger.info("Standard Supabase client initialized");
        return client;
    }
    
    /**
     * Create a Supabase DDL client bean for schema management operations.
     * 
     * @return The Supabase DDL client
     */
    @Bean
    public SupabaseDdlClient supabaseDdlClient() {
        logger.info("Initializing Supabase DDL client for schema management, Schema: {}", supabaseSchema);
        
        SupabaseDdlClient client = new SupabaseDdlClient(
                supabaseUrl,
                supabaseServiceRoleKey,
                supabaseSchema);
        
        logger.info("Supabase DDL client initialized");
        return client;
    }
}
