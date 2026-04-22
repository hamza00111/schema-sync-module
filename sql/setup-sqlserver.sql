-- ============================================================
-- Schema Sync Module: SQL Server Setup Script
-- Run this ONCE per database before starting the sync service.
-- ============================================================

-- 1. Enable CDC at the database level
EXEC sys.sp_cdc_enable_db;
GO

-- 2. Create the LSN tracking table.
--    Default name is dbo.SyncTracking. To use a different name, set
--    `schema-sync.tracking-table: <schema>.<table>` in application properties
--    and create the table below with that name instead.
CREATE TABLE dbo.SyncTracking (
    sync_name       VARCHAR(100)  PRIMARY KEY,
    last_lsn        BINARY(10)    NOT NULL,
    last_sync_time  DATETIME2     NOT NULL DEFAULT GETDATE(),
    changes_synced  BIGINT        NOT NULL DEFAULT 0,
    errors_count    BIGINT        NOT NULL DEFAULT 0
);
GO

-- 3. Loop-prevention marker table (RECOMMENDED).
--    The sync sink inserts one row here inside each write transaction. Because the
--    row shares its __$start_lsn with the user-table writes in the same transaction,
--    the CDC source can identify sync-origin changes without every source table
--    needing a `sync_source` column.
CREATE TABLE dbo.sync_markers (
    id          BIGINT IDENTITY PRIMARY KEY,
    sync_name   VARCHAR(100) NOT NULL,
    created_at  DATETIME2    NOT NULL DEFAULT SYSUTCDATETIME()
);
GO

EXEC sys.sp_cdc_enable_table
    @source_schema       = N'dbo',
    @source_name         = N'sync_markers',
    @role_name           = NULL,
    @supports_net_changes = 0;
GO

-- 4. (LEGACY FALLBACK) `sync_source` column on user tables.
--    Only needed if you are NOT using the marker table above. Kept for backwards
--    compatibility — ChangeEvent.isSyncOriginated() checks both mechanisms, so
--    you can migrate incrementally.

-- Legacy tables
ALTER TABLE dbo.Orders        ADD sync_source VARCHAR(10) DEFAULT 'APP';
ALTER TABLE dbo.OrderLines    ADD sync_source VARCHAR(10) DEFAULT 'APP';
ALTER TABLE dbo.OrderAddresses ADD sync_source VARCHAR(10) DEFAULT 'APP';

-- New tables
ALTER TABLE dbo.NewOrders     ADD sync_source VARCHAR(10) DEFAULT 'APP';
ALTER TABLE dbo.NewOrderItems ADD sync_source VARCHAR(10) DEFAULT 'APP';
GO

-- 5. Enable CDC on each table involved in sync.
--    @supports_net_changes = 1 is required for the sync module.

-- Legacy tables
EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name   = N'Orders',
    @role_name     = NULL,
    @supports_net_changes = 1;

EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name   = N'OrderLines',
    @role_name     = NULL,
    @supports_net_changes = 1;

EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name   = N'OrderAddresses',
    @role_name     = NULL,
    @supports_net_changes = 1;

-- New tables
EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name   = N'NewOrders',
    @role_name     = NULL,
    @supports_net_changes = 1;

EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name   = N'NewOrderItems',
    @role_name     = NULL,
    @supports_net_changes = 1;
GO

-- 6. (Optional) Increase CDC retention from default 3 days to 7 days
EXEC sys.sp_cdc_change_job
    @job_type = N'cleanup',
    @retention = 10080; -- minutes (7 days)
GO

-- 7. Monitoring query: check sync status
-- SELECT * FROM dbo.SyncTracking;

-- 8. Monitoring query: check CDC lag
-- SELECT
--     name AS capture_instance,
--     sys.fn_cdc_get_min_lsn(name) AS min_lsn,
--     sys.fn_cdc_get_max_lsn() AS max_lsn
-- FROM sys.dm_cdc_log_scan_sessions
-- WHERE name IS NOT NULL;
