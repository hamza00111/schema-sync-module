# Schema Sync Module

A reusable Spring Boot module for **bidirectional schema synchronization** during strangler fig migrations on SQL Server.

## The Problem

When migrating from a legacy schema to a new schema using the strangler fig pattern, you need:
- Both schemas to stay in sync while the migration is in progress
- The ability to roll back to the legacy module at any time without data loss
- Support for **N-to-M table mappings** (the new schema will have different table structure)

## Architecture

```
┌─────────────┐                                    ┌─────────────┐
│   Legacy     │──writes──▶ Legacy Tables ──CDC──┐  │   New        │
│   Module     │◀──reads─── Legacy Tables        │  │   Module     │
└─────────────┘                                  │  └──────┬──────┘
       ▲                                         ▼         │
       │                               ┌─────────────────┐ │
       │                               │   Sync Service   │ │
       │                               │                   │ │
       │                               │  ┌─────────────┐ │ │
       │                               │  │  CdcReader   │ │ │
       │                               │  └──────┬──────┘ │ │
       │                               │         ▼        │ │
       │                               │  ┌─────────────┐ │ │
       │                               │  │ SyncMapping  │ │ │
       │                               │  │  (your code) │ │ │
       │                               │  └──────┬──────┘ │ │
       │                               │         ▼        │ │
       │                               │  ┌─────────────┐ │ │
       │                               │  │   SyncSink   │ │ │
       │                               │  │ (JDBC / REST)│ │ │
       │                               │  └──────┬──────┘ │ │
       │                               └─────────┼───────┘ │
       │                                         ▼         ▼
       └────────CDC──── New Tables ◀──writes──────┘  ──writes──▶ New Tables
```

## What You Implement (per migration)

1. **`SyncMapping`** — one per direction, defines:
   - Which CDC capture instances to listen to (source tables)
   - How to transform changes into write commands (the N-to-M mapping logic)

2. **`ReconciliationCheck`** (optional) — drift detection between schemas

## What the Module Handles

- CDC polling and LSN tracking
- Loop prevention (session-based: SQL Server marker table / Postgres transaction-local GUC, with `sync_source` column as fallback)
- MERGE-based upserts and deletes on target tables
- Scheduling and error recovery
- Reconciliation engine

## Quick Start

### 1. Add dependency
```xml
<dependency>
    <groupId>com.yourcompany</groupId>
    <artifactId>schema-sync-module</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Run SQL setup
```bash
sqlcmd -S your-server -d your-db -i sql/setup.sql
```

### 3. Configure
```yaml
# application.yml
schema-sync:
  enabled: true
  poll-interval-ms: 5000
  reconciliation:
    enabled: true
    cron: "0 0 * * * *"  # every hour
```

### 4. Implement your mappings
```java
@Component
public class LegacyToNewOrdersMapping implements SyncMapping {

    @Override
    public String name() {
        return "legacy_orders_to_new_orders";
    }

    @Override
    public List<String> sourceCaptureInstances() {
        return List.of("dbo_Orders", "dbo_OrderLines");
    }

    @Override
    public List<SyncCommand> map(ChangeEvent event) {
        if (event.isSyncOriginated()) return List.of();
        
        // Your transformation logic here
        // One source change can produce multiple SyncCommands
        // targeting different tables
    }
}
```

### 5. That's it
The module auto-discovers your `SyncMapping` and `ReconciliationCheck` beans and runs them.

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| CDC over triggers | No application code changes to legacy, no write path overhead |
| Net changes mode | Collapses multiple changes to same row, reduces write volume |
| Session-based origin stamping | Loop prevention without schema changes on user tables |
| MERGE for writes | Atomic upsert, handles both insert and update |
| LSN tracking in DB | Survives service restarts, single source of truth |
| Reconciliation as separate concern | Safety net, doesn't block sync |

## Loop Prevention

Mappings call `event.isSyncOriginated()` and return an empty command list to skip sync-origin
changes. The flag is set at the **source** side — how depends on the platform:

**PostgreSQL** — transaction-local GUC. The sink calls
`set_config('sync.source', 'SYNC', true)` before its writes; the shared audit trigger
(`public.sync_record_change`) reads that GUC and stamps an `origin` column on each `sync_audit`
row. The CDC source maps the column onto `ChangeEvent.Origin.SYNC`. No user table needs a
`sync_source` column.

**SQL Server** — sidecar marker table. The sink inserts one row into `dbo.sync_markers`
inside each write transaction; because that row shares its `__$start_lsn` with the user-table
writes in the same transaction, the CDC source can identify sync-origin LSNs by reading
`cdc.dbo_sync_markers_CT` for the current poll range and flagging matching events. `CONTEXT_INFO`
/ `SESSION_CONTEXT` can't be used here because SQL Server CDC doesn't surface them on captured
rows.

**Fallback (legacy)** — the pre-existing `sync_source VARCHAR(10) DEFAULT 'APP'` column
convention is still honored: `ChangeEvent.isSyncOriginated()` returns `true` when either the
source-side origin is `SYNC` or the row has `sync_source = 'SYNC'`. Existing deployments keep
working; you can migrate off the column incrementally.

Both platform setup scripts (`sql/setup-sqlserver.sql`, `sql/setup-postgres.sql`) now create the
infrastructure automatically — apply them and the column-less path is enabled.

## Sinks

The write path is pluggable. A **`SyncSink`** receives the `SyncCommand`s produced by a mapping
and applies them to the target. Two built-in sinks ship:

- **`JdbcSyncSink`** — executes MERGE/DELETE through `JdbcTemplate` + `WriteDialect`. The
  adapter configs register one automatically under the bean name `"default"`, so existing
  mappings work with no code change.
- **`RestApiSyncSink`** — dispatches each command as an HTTP call via Spring's `RestClient`.
  Use this when the target is another service's REST API rather than its database. The call
  shape is produced by a `Function<SyncCommand, RestCall>`; `DefaultRestCallPlanner` handles
  the common "POST /{entity}" + "DELETE /{entity}/{id}" convention.

### Picking a sink per mapping

A mapping selects its sink by bean name via `SyncMapping.sinkName()` (default: `"default"`):

```java
@Bean
public SyncSink catalogRestSink() {
    RestClient client = RestClient.builder().baseUrl("https://catalog.internal").build();
    return new RestApiSyncSink("catalog-rest", client, new DefaultRestCallPlanner());
}

@Component
public class OrdersToCatalogMapping implements SyncMapping<ByteArrayPosition> {
    @Override public String sinkName() { return "catalog-rest"; }
    // ...
}
```

### NEW → LEGACY: outbox as source

For the reverse direction (push changes from the new module back to legacy), implement a
`CdcSource<Long>` that reads from a **transactional outbox** table in the new module's DB and
wire it to a `JdbcSyncSink` pointing at the legacy database. The outbox schema is
application-specific; loop prevention on the legacy side uses the same marker-table mechanism
documented above (the `JdbcSyncSink` stamps `dbo.sync_markers` automatically inside its write
transaction).

## Monitoring

Check the `SyncTracking` table:
```sql
SELECT sync_name, last_sync_time, changes_synced, errors_count
FROM dbo.SyncTracking;
```

## File Structure

```
schema-sync-module/
├── src/main/java/com/sync/
│   ├── config/
│   │   ├── SyncAutoConfiguration.java   -- Spring Boot auto-config
│   │   └── SyncProperties.java          -- Configuration properties
│   ├── core/
│   │   ├── SyncMapping.java             -- ★ Core abstraction (you implement this)
│   │   └── SyncEngine.java              -- Orchestrates one sync cycle
│   ├── cdc/
│   │   ├── CdcReader.java               -- Reads CDC change tables
│   │   └── LsnTracker.java              -- Persists sync progress
│   ├── model/
│   │   ├── ChangeEvent.java             -- CDC change representation
│   │   └── SyncCommand.java             -- Write command representation
│   ├── writer/
│   │   ├── JdbcSyncSink.java            -- Executes MERGE/DELETE
│   │   ├── RestApiSyncSink.java         -- Dispatches commands as HTTP calls
│   │   ├── DefaultRestCallPlanner.java  -- SyncCommand → POST/DELETE default
│   │   └── RestCall.java                -- (verb, path, body) record
│   ├── reconciliation/
│   │   ├── ReconciliationCheck.java      -- ★ Drift detection (you implement this)
│   │   └── ReconciliationEngine.java     -- Runs checks on schedule
│   └── scheduler/
│       └── SyncScheduler.java            -- Auto-discovers and runs mappings
├── sql/
│   └── setup.sql                         -- Database setup script
└── README.md
```
