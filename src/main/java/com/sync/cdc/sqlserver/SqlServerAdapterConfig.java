package com.sync.cdc.sqlserver;

import com.sync.cdc.spi.CdcSource;
import com.sync.cdc.spi.HealthQueries;
import com.sync.cdc.spi.LsnStore;
import com.sync.cdc.spi.WriteDialect;
import com.sync.config.SyncProperties;
import com.sync.core.SyncEngine;
import com.sync.writer.SyncWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "schema-sync.platform", havingValue = "sqlserver")
public class SqlServerAdapterConfig {

    @Bean
    public CdcSource<ByteArrayPosition> cdcSource(JdbcTemplate jdbc) {
        return new SqlServerCdcSource(jdbc);
    }

    @Bean
    public LsnStore<ByteArrayPosition> lsnStore(JdbcTemplate jdbc, SyncProperties props) {
        return new SqlServerLsnStore(jdbc, resolveTrackingTable(props));
    }

    @Bean
    public WriteDialect writeDialect() {
        return new SqlServerWriteDialect();
    }

    @Bean
    public HealthQueries healthQueries(SyncProperties props) {
        return new SqlServerHealthQueries(resolveTrackingTable(props));
    }

    private static String resolveTrackingTable(SyncProperties props) {
        String configured = props.getTrackingTable();
        return (configured == null || configured.isBlank())
                ? SqlServerLsnStore.DEFAULT_TRACKING_TABLE
                : configured;
    }

    @Bean
    public SyncWriter syncWriter(JdbcTemplate jdbc, WriteDialect dialect) {
        return new SyncWriter(jdbc, dialect);
    }

    @Bean
    public SyncEngine<ByteArrayPosition> syncEngine(
            CdcSource<ByteArrayPosition> cdcSource,
            LsnStore<ByteArrayPosition> lsnStore,
            SyncWriter syncWriter) {
        return new SyncEngine<>(cdcSource, lsnStore, syncWriter);
    }
}
