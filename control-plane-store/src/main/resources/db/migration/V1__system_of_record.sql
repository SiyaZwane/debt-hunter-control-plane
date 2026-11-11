-- The control plane's system of record.
--
-- Contributor identity never appears here: findings carry pseudonyms only. The pseudonym -> person
-- directory is deliberately NOT in this schema; it is a separate, encrypted, access-restricted store.

CREATE TABLE run (
    applied_seq     BIGSERIAL PRIMARY KEY,
    run_id          TEXT        NOT NULL UNIQUE,
    repository      TEXT        NOT NULL,
    project_slice   TEXT        NOT NULL,
    commit_id       TEXT        NOT NULL,
    tool_version    TEXT,
    composite_key   TEXT        NOT NULL,
    degraded        BOOLEAN     NOT NULL,
    scope_limited   BOOLEAN     NOT NULL,
    committed_at    TIMESTAMPTZ,
    status          TEXT        NOT NULL,
    superseded_by   TEXT
);
CREATE INDEX run_composite_key ON run (composite_key);
CREATE INDEX run_slice_status ON run (repository, project_slice, status, applied_seq);

CREATE TABLE run_engine (
    run_id     TEXT    NOT NULL REFERENCES run (run_id),
    ordinal    INTEGER NOT NULL,
    engine_id  TEXT    NOT NULL,
    status     TEXT    NOT NULL,
    PRIMARY KEY (run_id, ordinal)
);

CREATE TABLE finding_state (
    repository            TEXT NOT NULL,
    project_slice         TEXT NOT NULL,
    fingerprint           TEXT NOT NULL,
    state                 TEXT NOT NULL,
    last_observed_commit  TEXT,
    PRIMARY KEY (repository, project_slice, fingerprint)
);

-- Append-only. The application has no UPDATE or DELETE for this table, and event_id makes a retried
-- append a no-op.
CREATE TABLE transition_log (
    seq            BIGSERIAL PRIMARY KEY,
    event_id       TEXT        NOT NULL UNIQUE,
    repository     TEXT        NOT NULL,
    project_slice  TEXT        NOT NULL,
    fingerprint    TEXT        NOT NULL,
    from_state     TEXT,
    to_state       TEXT        NOT NULL,
    run_id         TEXT        NOT NULL,
    event_time     TIMESTAMPTZ NOT NULL,
    cause          TEXT        NOT NULL
);
CREATE INDEX transition_log_finding ON transition_log (repository, project_slice, fingerprint, seq);
CREATE INDEX transition_log_run ON transition_log (run_id, seq);

CREATE TABLE finding (
    run_id        TEXT             NOT NULL,
    ordinal       INTEGER          NOT NULL,
    repository    TEXT             NOT NULL,
    finding_id    TEXT             NOT NULL,
    rule_id       TEXT             NOT NULL,
    category      TEXT             NOT NULL,
    severity      TEXT             NOT NULL,
    confidence    DOUBLE PRECISION NOT NULL,
    path          TEXT             NOT NULL,
    start_line    INTEGER          NOT NULL,
    message       TEXT             NOT NULL,
    evidence      TEXT             NOT NULL,
    score         DOUBLE PRECISION NOT NULL,
    is_new        BOOLEAN          NOT NULL,
    fingerprint   TEXT             NOT NULL,
    contributors  TEXT             NOT NULL,
    PRIMARY KEY (run_id, ordinal)
);

CREATE TABLE onboarded_repository (
    repository             TEXT PRIMARY KEY,
    owner                  TEXT NOT NULL,
    tier                   TEXT NOT NULL,
    policy_bundle_version  TEXT NOT NULL,
    rollout_stage          TEXT NOT NULL,
    route_tracker          TEXT NOT NULL,
    route_target           TEXT NOT NULL
);

CREATE TABLE workload_scope (
    workload_identity  TEXT NOT NULL,
    repository         TEXT NOT NULL,
    PRIMARY KEY (workload_identity, repository)
);

CREATE TABLE suppression (
    suppression_id          TEXT PRIMARY KEY,
    repository              TEXT        NOT NULL,
    fingerprint             TEXT        NOT NULL,
    reason                  TEXT        NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    max_expiry_days         INTEGER     NOT NULL,
    pre_expiry_notified_at  TIMESTAMPTZ,
    UNIQUE (repository, fingerprint)
);

-- Pointer only: where the baseline artefact lives, never its content.
CREATE TABLE baseline (
    repository     TEXT        NOT NULL,
    project_slice  TEXT        NOT NULL,
    commit_id      TEXT        NOT NULL,
    oci_reference  TEXT        NOT NULL,
    digest         TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    provenance     TEXT        NOT NULL,
    PRIMARY KEY (repository, project_slice, commit_id)
);

CREATE TABLE policy_bundle (
    version         TEXT PRIMARY KEY,
    digest          TEXT        NOT NULL,
    ratified_by     TEXT,
    ratified_at     TIMESTAMPTZ,
    effective_from  TIMESTAMPTZ,
    superseded_by   TEXT
);

-- Hot tier: full evidence, kept until expires_at.
CREATE TABLE evidence_hot (
    snapshot_id  TEXT PRIMARY KEY,
    run_id       TEXT        NOT NULL,
    repository   TEXT        NOT NULL,
    finding_id   TEXT        NOT NULL,
    fingerprint  TEXT        NOT NULL,
    rule_id      TEXT        NOT NULL,
    severity     TEXT        NOT NULL,
    evidence     TEXT        NOT NULL,
    captured_at  TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX evidence_hot_expiry ON evidence_hot (expires_at);

-- Warm tier: what remains once the detail has expired.
CREATE TABLE evidence_warm (
    repository     TEXT    NOT NULL,
    rule_id        TEXT    NOT NULL,
    severity       TEXT    NOT NULL,
    day            DATE    NOT NULL,
    finding_count  INTEGER NOT NULL,
    PRIMARY KEY (repository, rule_id, severity, day)
);

-- Append-only, enforced by the database and not only by the application: no UPDATE, DELETE or
-- TRUNCATE can touch the transition log, whatever code (or person) is connected.
CREATE FUNCTION transition_log_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'transition_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transition_log_no_row_change
    BEFORE UPDATE OR DELETE ON transition_log
    FOR EACH ROW EXECUTE FUNCTION transition_log_is_append_only();

CREATE TRIGGER transition_log_no_truncate
    BEFORE TRUNCATE ON transition_log
    FOR EACH STATEMENT EXECUTE FUNCTION transition_log_is_append_only();
