-- Administrative changes, re-identification requests and suppressions: who did what, and whether it
-- was allowed. Append-only for the application except for the retention purge, which never removes
-- entries younger than ninety days.
CREATE TABLE audit_trail (
    id          BIGSERIAL PRIMARY KEY,
    kind        TEXT        NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    caller      TEXT        NOT NULL,
    action      TEXT        NOT NULL,
    target      TEXT        NOT NULL,
    detail      TEXT        NOT NULL,
    outcome     TEXT        NOT NULL
);
CREATE INDEX audit_trail_kind_time ON audit_trail (kind, recorded_at);
