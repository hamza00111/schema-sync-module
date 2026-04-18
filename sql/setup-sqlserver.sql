-- ============================================================
-- Schema Sync Module: SQL Server Setup Script
-- Run this ONCE per database before starting the sync service.
-- ============================================================

-- 1. Enable CDC at the database level
EXEC sys.sp_cdc_enable_db;
GO

-- 2. Create the LSN tracking table
CREATE TABLE dbo.SyncTracking (
    sync_name       VARCHAR(100)  PRIMARY KEY,
    last_lsn        BINARY(10)    NOT NULL,
    last_sync_time  DATETIME2     NOT NULL DEFAULT GETDATE(),
    changes_synced  BIGINT        NOT NULL DEFAULT 0,
    errors_count    BIGINT        NOT NULL DEFAULT 0
);
GO

-- 3. Add sync_source column to ALL tables involved in sync.
--    This prevents infinite sync loops.
--    Repeat for each table:

-- Legacy tables
ALTER TABLE dbo.Orders        ADD sync_source VARCHAR(10) DEFAULT 'APP';
ALTER TABLE dbo.OrderLines    ADD sync_source VARCHAR(10) DEFAULT 'APP';
ALTER TABLE dbo.OrderAddresses ADD sync_source VARCHAR(10) DEFAULT 'APP';

-- New tables
ALTER TABLE dbo.NewOrders     ADD sync_source VARCHAR(10) DEFAULT 'APP';
ALTER TABLE dbo.NewOrderItems ADD sync_source VARCHAR(10) DEFAULT 'APP';
GO

-- 4. Enable CDC on each table involved in sync.
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

-- 5. (Optional) Increase CDC retention from default 3 days to 7 days
EXEC sys.sp_cdc_change_job
    @job_type = N'cleanup',
    @retention = 10080; -- minutes (7 days)
GO

-- 6. Monitoring query: check sync status
-- SELECT * FROM dbo.SyncTracking;

-- 7. Monitoring query: check CDC lag
-- SELECT
--     name AS capture_instance,
--     sys.fn_cdc_get_min_lsn(name) AS min_lsn,
--     sys.fn_cdc_get_max_lsn() AS max_lsn
-- FROM sys.dm_cdc_log_scan_sessions
-- WHERE name IS NOT NULL;
