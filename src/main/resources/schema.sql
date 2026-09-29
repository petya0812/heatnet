CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS data;

CREATE TABLE IF NOT EXISTS dataset (
    id             uuid PRIMARY KEY,
    name           text,
    status         text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    finished_at    timestamptz,
    file_bytes     bigint,
    feature_count  bigint,
    imported_count bigint,
    import_millis  bigint,
    type_counts    jsonb,
    issue_counts   jsonb,
    error          text
);

CREATE TABLE IF NOT EXISTS dataset_issue (
    dataset_id uuid NOT NULL REFERENCES dataset (id) ON DELETE CASCADE,
    severity   text NOT NULL,
    code       text NOT NULL,
    feature_id text,
    message    text
);

CREATE INDEX IF NOT EXISTS dataset_issue_dataset ON dataset_issue (dataset_id);

CREATE TABLE IF NOT EXISTS run (
    id           uuid PRIMARY KEY,
    dataset_id   uuid        NOT NULL REFERENCES dataset (id) ON DELETE CASCADE,
    status       text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    started_at   timestamptz,
    finished_at  timestamptz,
    result_path  text,
    result_bytes bigint,
    summary      jsonb,
    validation   jsonb,
    diagnostics  jsonb,
    error        text
);

CREATE INDEX IF NOT EXISTS run_dataset ON run (dataset_id);

-- map and per-run parameters
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS bbox double precision[];
ALTER TABLE run ADD COLUMN IF NOT EXISTS params jsonb;
ALTER TABLE run ADD COLUMN IF NOT EXISTS result_table boolean NOT NULL DEFAULT false;

-- versions of a dataset with edits of the input; names, notes, pins and journals of runs
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS parent_id uuid;
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS root_id uuid;
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS version int NOT NULL DEFAULT 1;
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS edits jsonb;
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS note text;
CREATE INDEX IF NOT EXISTS dataset_root ON dataset (root_id);
ALTER TABLE run ADD COLUMN IF NOT EXISTS name text;
ALTER TABLE run ADD COLUMN IF NOT EXISTS note text;
ALTER TABLE run ADD COLUMN IF NOT EXISTS pinned boolean NOT NULL DEFAULT false;
ALTER TABLE run ADD COLUMN IF NOT EXISTS journal_path text;
