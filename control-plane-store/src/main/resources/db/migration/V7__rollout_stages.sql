-- Where each repository is in the rollout, and the append-only log of how it got there.
CREATE TABLE rollout_stage_record (
    repository             TEXT PRIMARY KEY,
    stage                  INTEGER     NOT NULL CHECK (stage BETWEEN 1 AND 4),
    effective_from         TIMESTAMPTZ NOT NULL,
    owner                  TEXT        NOT NULL CHECK (owner <> ''),
    exit_evidence          TEXT        NOT NULL,
    exemption_reason       TEXT,
    exemption_approved_by  TEXT,
    exemption_granted_at   TIMESTAMPTZ,
    exemption_expires_at   TIMESTAMPTZ,
    escalated_at           TIMESTAMPTZ
);

CREATE TABLE rollout_stage_event (
    seq         BIGSERIAL PRIMARY KEY,
    repository  TEXT        NOT NULL,
    kind        TEXT        NOT NULL,
    from_stage  INTEGER,
    to_stage    INTEGER     NOT NULL,
    at          TIMESTAMPTZ NOT NULL,
    actor       TEXT        NOT NULL CHECK (actor <> ''),
    criteria    TEXT        NOT NULL,
    reason      TEXT
);
CREATE INDEX rollout_stage_event_repository ON rollout_stage_event (repository, seq);

-- The event log is the audit trail of who moved what, and it must not be editable by anyone.
CREATE FUNCTION rollout_stage_event_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'rollout_stage_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rollout_stage_event_no_row_change
    BEFORE UPDATE OR DELETE ON rollout_stage_event
    FOR EACH ROW EXECUTE FUNCTION rollout_stage_event_is_append_only();

CREATE TRIGGER rollout_stage_event_no_truncate
    BEFORE TRUNCATE ON rollout_stage_event
    FOR EACH STATEMENT EXECUTE FUNCTION rollout_stage_event_is_append_only();
