package com.sync.cdc.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sync.cdc.spi.CdcSource;
import com.sync.cdc.spi.HealthQueries;
import com.sync.cdc.spi.LsnStore;
import com.sync.cdc.spi.WriteDialect;
import com.sync.core.SyncEngine;
import com.sync.writer.SyncWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "schema-sync.platform", havingValue = "postgres")
public class PostgresAdapterConfig {

    @Bean
    public PostgresJsonReader postgresJsonReader(ObjectMapper mapper) {
        return new PostgresJsonReader(mapper);
    }

    @Bean
    public CdcSource<Long> cdcSource(JdbcTemplate jdbc, PostgresJsonReader reader) {
        return new PostgresCdcSource(jdbc, reader);
    }

    @Bean
    public LsnStore<Long> lsnStore(JdbcTemplate jdbc) {
        return new PostgresLsnStore(jdbc);
    }

    @Bean
    public WriteDialect writeDialect() {
        return new PostgresWriteDialect();
    }

    @Bean
    public HealthQueries healthQueries() {
        return new PostgresHealthQueries();
    }

    @Bean
    public SyncWriter syncWriter(JdbcTemplate jdbc, WriteDialect dialect) {
        return new SyncWriter(jdbc, dialect);
    }

    @Bean
    public SyncEngine<Long> syncEngine(
            CdcSource<Long> cdcSource,
            LsnStore<Long> lsnStore,
            SyncWriter syncWriter) {
        return new SyncEngine<>(cdcSource, lsnStore, syncWriter);
    }
}
