-- The policy bundle version each run was assessed against, so a run can be explained by the policy
-- that was in force for it, not the one in force now. Runs received earlier have none.
ALTER TABLE run ADD COLUMN policy_bundle_version TEXT;

-- Every request for a run's evidence: who, which run, and the purpose they gave. Append-only; the
-- application has no UPDATE or DELETE for it.
CREATE TABLE evidence_access_log (
    id           BIGSERIAL PRIMARY KEY,
    accessed_at  TIMESTAMPTZ NOT NULL,
    caller       TEXT        NOT NULL,
    run_id       TEXT        NOT NULL,
    repository   TEXT,
    purpose      TEXT        NOT NULL,
    outcome      TEXT        NOT NULL
);
CREATE INDEX evidence_access_log_time ON evidence_access_log (accessed_at);
