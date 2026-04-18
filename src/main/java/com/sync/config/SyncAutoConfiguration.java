package com.sync.config;

import com.sync.cdc.postgres.PostgresAdapterConfig;
import com.sync.cdc.sqlserver.SqlServerAdapterConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration for the Schema Sync module.
 *
 * <p>Consumers:
 * <ol>
 *   <li>Add the module as a Maven/Gradle dependency.</li>
 *   <li>Set {@code schema-sync.platform} = {@code postgres} or {@code sqlserver} (required).</li>
 *   <li>Set {@code schema-sync.enabled: true}.</li>
 *   <li>Implement {@code SyncMapping} beans for your specific tables.</li>
 *   <li>Optionally implement {@code ReconciliationCheck} beans.</li>
 * </ol>
 *
 * <p>Everything else (CDC reading, position tracking, scheduling, writing) is handled.
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(SyncProperties.class)
@ConditionalOnProperty(name = "schema-sync.enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "com.sync")
@Import({SqlServerAdapterConfig.class, PostgresAdapterConfig.class})
public class SyncAutoConfiguration {
}
