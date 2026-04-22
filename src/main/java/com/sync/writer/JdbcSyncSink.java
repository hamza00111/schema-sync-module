package com.sync.writer;

import com.sync.cdc.spi.SyncSink;
import com.sync.cdc.spi.WriteDialect;
import com.sync.core.SyncMapping;
import com.sync.model.SyncCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@link SyncSink} that executes {@link SyncCommand}s as MERGE/DELETE against a relational target.
 *
 * <p>SQL is built by the injected {@link WriteDialect} (platform-specific).
 * All commands in a call are run in one transaction.
 */
public class JdbcSyncSink implements SyncSink {

    private static final Logger log = LoggerFactory.getLogger(JdbcSyncSink.class);

    private final String name;
    private final JdbcTemplate jdbc;
    private final WriteDialect dialect;

    public JdbcSyncSink(String name, JdbcTemplate jdbc, WriteDialect dialect) {
        this.name = name;
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    @Transactional
    public void dispatch(SyncMapping<?> mapping, List<SyncCommand> commands) {
        // Stamp this transaction as sync-origin so our CDC source can flag the resulting
        // ChangeEvents with Origin.SYNC and downstream mappings can skip them.
        dialect.stampSyncOrigin(jdbc, mapping.name());

        for (SyncCommand cmd : commands) {
            switch (cmd.type()) {
                case UPSERT -> executeUpsert(cmd);
                case DELETE -> executeDelete(cmd);
            }
        }
    }

    private void executeUpsert(SyncCommand cmd) {
        dialect.validateTableName(cmd.targetTable());

        Map<String, Object> values = cmd.values();
        List<String> allColumns = values.keySet().stream().toList();
        List<String> keyColumns = Arrays.asList(cmd.keyColumns());

        String sql = dialect.buildUpsert(cmd.targetTable(), allColumns, keyColumns);

        Object[] params = allColumns.stream()
                .map(values::get)
                .toArray();

        jdbc.update(sql, params);

        log.debug("Upserted into {}: keys={}", cmd.targetTable(),
                Arrays.toString(cmd.keyColumns()));
    }

    private void executeDelete(SyncCommand cmd) {
        dialect.validateTableName(cmd.targetTable());

        String[] keyColumns = cmd.keyColumns();
        Map<String, Object> values = cmd.values();

        String sql = dialect.buildDelete(cmd.targetTable(), Arrays.asList(keyColumns));

        Object[] params = Arrays.stream(keyColumns)
                .map(values::get)
                .toArray();

        jdbc.update(sql, params);

        log.debug("Deleted from {}: keys={}", cmd.targetTable(),
                Arrays.toString(keyColumns));
    }
}
