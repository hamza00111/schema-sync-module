-- ============================================================================
-- schema-sync-module — PostgreSQL setup
-- ============================================================================
-- This script creates:
--   1. public.sync_audit         -- trigger-populated CDC-like feed
--   2. public.sync_record_change -- shared trigger function
--   3. public.sync_tracking      -- per-mapping position bookmarks
--
-- After running this, apply the trigger DDL (bottom of file) for each source
-- table you want synced. Every synced table MUST have a primary key.
--
-- Loop prevention: the sync sink calls `set_config('sync.source', 'SYNC', true)`
-- inside every write transaction; the trigger function reads that GUC and
-- stamps the `origin` column on each sync_audit row. This means source tables
-- do NOT need a `sync_source` column. (The legacy column convention is still
-- honored as a fallback — see `ChangeEvent.isSyncOriginated`.)
-- ============================================================================


-- 1. Audit table --------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.sync_audit (
    id          BIGSERIAL PRIMARY KEY,
    table_name  TEXT        NOT NULL,
    op          CHAR(1)     NOT NULL CHECK (op IN ('I', 'U', 'D')),
    pk_json     JSONB       NOT NULL,
    row_json    JSONB       NOT NULL,
    origin      CHAR(4),                          -- 'APP' | 'SYNC' | NULL (pre-migration rows)
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Safe to run on an older schema that pre-dates the `origin` column.
ALTER TABLE public.sync_audit
    ADD COLUMN IF NOT EXISTS origin CHAR(4);

CREATE INDEX IF NOT EXISTS idx_sync_audit_table_id
    ON public.sync_audit (table_name, id);


-- 2. Shared trigger function --------------------------------------------------
-- Reads the primary key columns from information_schema at trigger-exec time,
-- captures old/new row as JSONB, and writes one row to sync_audit.
-- The `origin` column is stamped from the transaction-local GUC `sync.source`,
-- which the sync sink sets to 'SYNC' before it writes.
CREATE OR REPLACE FUNCTION public.sync_record_change()
RETURNS TRIGGER AS $$
DECLARE
    pk_cols   TEXT[];
    pk_obj    JSONB;
    row_obj   JSONB;
    op_code   CHAR(1);
    origin_v  TEXT;
BEGIN
    -- Resolve primary key columns for this table
    SELECT ARRAY_AGG(a.attname ORDER BY a.attnum)
      INTO pk_cols
      FROM pg_index i
      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
     WHERE i.indrelid = TG_RELID
       AND i.indisprimary;

    IF pk_cols IS NULL THEN
        RAISE EXCEPTION 'sync_record_change: table %.% has no primary key',
            TG_TABLE_SCHEMA, TG_TABLE_NAME;
    END IF;

    IF TG_OP = 'INSERT' THEN
        op_code := 'I';
        row_obj := to_jsonb(NEW);
    ELSIF TG_OP = 'UPDATE' THEN
        op_code := 'U';
        row_obj := to_jsonb(NEW);
    ELSE
        op_code := 'D';
        row_obj := to_jsonb(OLD);
    END IF;

    -- Extract PK subset from row_obj using the resolved column list
    SELECT jsonb_object_agg(k, row_obj -> k)
      INTO pk_obj
      FROM unnest(pk_cols) AS k;

    -- Read transaction-local GUC set by the sync sink; default to 'APP' otherwise.
    origin_v := current_setting('sync.source', true);
    IF origin_v IS NULL OR origin_v = '' THEN
        origin_v := 'APP';
    END IF;

    INSERT INTO public.sync_audit (table_name, op, pk_json, row_json, origin)
    VALUES (TG_TABLE_NAME, op_code, pk_obj, row_obj, origin_v);

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;


-- 3. Tracking table -----------------------------------------------------------
--    Default name is public.sync_tracking. To use a different name, set
--    `schema-sync.tracking-table: <schema>.<table>` in application properties
--    and create the table below with that name instead.
CREATE TABLE IF NOT EXISTS public.sync_tracking (
    sync_name       TEXT        PRIMARY KEY,
    last_position   BIGINT      NOT NULL,
    last_sync_time  TIMESTAMPTZ NOT NULL DEFAULT now(),
    changes_synced  BIGINT      NOT NULL DEFAULT 0,
    errors_count    BIGINT      NOT NULL DEFAULT 0
);


-- ============================================================================
-- Per-table trigger installation template
-- ============================================================================
-- For each source table you want synced, run:
--
--   DROP TRIGGER IF EXISTS {{TABLE}}_sync_audit ON {{TABLE}};
--   CREATE TRIGGER {{TABLE}}_sync_audit
--       AFTER INSERT OR UPDATE OR DELETE ON {{TABLE}}
--       FOR EACH ROW EXECUTE FUNCTION public.sync_record_change();
--
-- No `sync_source` column needed — loop prevention is handled by the
-- transaction-local GUC + `origin` column. If you are migrating from an older
-- setup that uses a `sync_source` column, you may leave it in place; the
-- fallback in ChangeEvent.isSyncOriginated() continues to honor it.
-- ============================================================================
