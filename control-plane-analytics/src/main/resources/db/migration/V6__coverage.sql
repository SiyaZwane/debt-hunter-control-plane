-- The latest coverage reconciliation: what each repository's state was when the registry was last
-- compared with the source-control estate. Replaced wholesale by each reconciliation.
CREATE TABLE rm_coverage_report (
    id                INTEGER PRIMARY KEY CHECK (id = 1),
    reconciled_at     TIMESTAMPTZ NOT NULL,
    estate_available  BOOLEAN     NOT NULL,
    warnings          TEXT        NOT NULL
);

CREATE TABLE rm_coverage (
    repository            TEXT PRIMARY KEY,
    state                 TEXT        NOT NULL,
    reasons               TEXT        NOT NULL,
    last_run_received_at  TIMESTAMPTZ,
    last_tool_version     TEXT,
    onboarded             BOOLEAN     NOT NULL,
    in_estate             BOOLEAN
);
