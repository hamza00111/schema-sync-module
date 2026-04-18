-- ============================================================================
-- schema-sync-module — PostgreSQL setup
-- ============================================================================
-- This script creates:
--   1. public.sync_audit         -- trigger-populated CDC-like feed
--   2. public.sync_record_change -- shared trigger function
--   3. public.sync_tracking      -- per-mapping position bookmarks
--
-- After running this, apply the trigger DDL (bottom of file) for each source
-- table you want synced. Every synced table MUST have:
--   * a primary key
--   * a `sync_source VARCHAR(10) DEFAULT 'APP'` column (for loop prevention)
-- ============================================================================


-- 1. Audit table --------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.sync_audit (
    id          BIGSERIAL PRIMARY KEY,
    table_name  TEXT        NOT NULL,
    op          CHAR(1)     NOT NULL CHECK (op IN ('I', 'U', 'D')),
    pk_json     JSONB       NOT NULL,
    row_json    JSONB       NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sync_audit_table_id
    ON public.sync_audit (table_name, id);


-- 2. Shared trigger function --------------------------------------------------
-- Reads the primary key columns from information_schema at trigger-exec time,
-- captures old/new row as JSONB, and writes one row to sync_audit.
CREATE OR REPLACE FUNCTION public.sync_record_change()
RETURNS TRIGGER AS $$
DECLARE
    pk_cols  TEXT[];
    pk_obj   JSONB;
    row_obj  JSONB;
    op_code  CHAR(1);
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

    INSERT INTO public.sync_audit (table_name, op, pk_json, row_json)
    VALUES (TG_TABLE_NAME, op_code, pk_obj, row_obj);

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;


-- 3. Tracking table -----------------------------------------------------------
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
-- For each source table you want synced, run the block below. Replace
-- {{TABLE}} with the unqualified table name (e.g. `orders`).
--
--   ALTER TABLE {{TABLE}}
--       ADD COLUMN IF NOT EXISTS sync_source VARCHAR(10) NOT NULL DEFAULT 'APP';
--
--   DROP TRIGGER IF EXISTS {{TABLE}}_sync_audit ON {{TABLE}};
--   CREATE TRIGGER {{TABLE}}_sync_audit
--       AFTER INSERT OR UPDATE OR DELETE ON {{TABLE}}
--       FOR EACH ROW EXECUTE FUNCTION public.sync_record_change();
-- ============================================================================
