-- Bundles that can never be processed, set aside for a person to look at.
--
-- Metadata and the reason ONLY. The bundle itself is deliberately not stored: it still carries raw
-- contributor identity, which must never reach this database. The original stays on the intake topic
-- until its retention expires, which is where a person goes to inspect it.
CREATE TABLE quarantined_bundle (
    correlation_id     TEXT PRIMARY KEY,
    run_id             TEXT        NOT NULL,
    repository         TEXT        NOT NULL,
    workload_identity  TEXT        NOT NULL,
    schema_version     TEXT        NOT NULL,
    reason             TEXT        NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL,
    quarantined_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX quarantined_bundle_run ON quarantined_bundle (run_id);
