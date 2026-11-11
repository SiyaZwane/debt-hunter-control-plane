-- Materialised read models for analytics. Everything here is derived from the system of record and
-- can be rebuilt from it; nothing here is consulted by the ingest path.

-- How far the projector has read in each source, advanced in the same transaction as the writes it
-- covers, so a batch is applied exactly once.
CREATE TABLE rm_checkpoint (
    name      TEXT PRIMARY KEY,
    last_seq  BIGINT NOT NULL
);

-- When a projection pass last completed, even one that found nothing new: this is what staleness is
-- judged against.
CREATE TABLE rm_heartbeat (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    last_updated  TIMESTAMPTZ NOT NULL
);

-- One row per commit analysed: finding counts by severity, and the quantities trends are drawn from.
CREATE TABLE rm_run_snapshot (
    repository               TEXT             NOT NULL,
    project_slice            TEXT             NOT NULL,
    commit_id                TEXT             NOT NULL,
    run_id                   TEXT             NOT NULL,
    committed_at             TIMESTAMPTZ      NOT NULL,
    critical                 INTEGER          NOT NULL,
    high                     INTEGER          NOT NULL,
    medium                   INTEGER          NOT NULL,
    low                      INTEGER          NOT NULL,
    info                     INTEGER          NOT NULL,
    architecture_violations  INTEGER          NOT NULL,
    complexity_delta         DOUBLE PRECISION NOT NULL,
    policy_status            TEXT,
    PRIMARY KEY (repository, project_slice, commit_id)
);
CREATE INDEX rm_run_snapshot_time ON rm_run_snapshot (repository, project_slice, committed_at);

CREATE TABLE rm_hotspot (
    repository        TEXT             NOT NULL,
    project_slice     TEXT             NOT NULL,
    commit_id         TEXT             NOT NULL,
    path              TEXT             NOT NULL,
    committed_at      TIMESTAMPTZ      NOT NULL,
    change_frequency  DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (repository, project_slice, commit_id, path)
);
CREATE INDEX rm_hotspot_path ON rm_hotspot (repository, project_slice, path, committed_at);

-- Transitions per rule per day (UTC, by commit date).
CREATE TABLE rm_rule_daily (
    repository     TEXT    NOT NULL,
    project_slice  TEXT    NOT NULL,
    rule_id        TEXT    NOT NULL,
    day            DATE    NOT NULL,
    created        INTEGER NOT NULL,
    resolved       INTEGER NOT NULL,
    regressed      INTEGER NOT NULL,
    PRIMARY KEY (repository, project_slice, rule_id, day)
);

-- Findings that are open right now and since when; the other half of a time-to-resolution.
CREATE TABLE rm_open_finding (
    repository     TEXT        NOT NULL,
    project_slice  TEXT        NOT NULL,
    fingerprint    TEXT        NOT NULL,
    rule_id        TEXT        NOT NULL,
    severity       TEXT        NOT NULL,
    category       TEXT        NOT NULL,
    since          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (repository, project_slice, fingerprint)
);

CREATE TABLE rm_resolution (
    repository        TEXT        NOT NULL,
    project_slice     TEXT        NOT NULL,
    fingerprint       TEXT        NOT NULL,
    rule_id           TEXT        NOT NULL,
    severity          TEXT        NOT NULL,
    category          TEXT        NOT NULL,
    resolved_at       TIMESTAMPTZ NOT NULL,
    duration_seconds  BIGINT      NOT NULL,
    PRIMARY KEY (repository, project_slice, fingerprint, resolved_at)
);
CREATE INDEX rm_resolution_time ON rm_resolution (repository, project_slice, resolved_at);
