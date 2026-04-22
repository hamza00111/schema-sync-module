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
- Loop prevention (via `sync_source` column convention)
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
| `sync_source` column | Simple, debuggable loop prevention |
| MERGE for writes | Atomic upsert, handles both insert and update |
| LSN tracking in DB | Survives service restarts, single source of truth |
| Reconciliation as separate concern | Safety net, doesn't block sync |

## Loop Prevention

Every table must have a `sync_source VARCHAR(10) DEFAULT 'APP'` column.
- Application writes → `sync_source = 'APP'` (the default)
- Sync writes → `sync_source = 'SYNC'` (set explicitly by the sink)
- SyncMapping implementations call `event.isSyncOriginated()` to skip sync-originated changes

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
wire it to a `JdbcSyncSink` pointing at the legacy database. The outbox schema and loop-break
strategy (e.g. a `SET CONTEXT_INFO 0x...` wrap so legacy CDC can filter the echo) are
application-specific; define them to match your legacy CDC filters.

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
