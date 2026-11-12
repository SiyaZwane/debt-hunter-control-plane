-- Work-item egress: which tracker item stands for which finding, and how far the feeder has read the
-- transition log. Nothing here is consulted by the ingest path.

-- At most one row per finding, whatever state the item is in. The row is reserved before the tracker
-- is called and the tracker item is found by its key if a crash left the row without an item id, so a
-- finding can never end up with two items.
CREATE TABLE work_item_link (
    repository     TEXT        NOT NULL,
    project_slice  TEXT        NOT NULL,
    fingerprint    TEXT        NOT NULL,
    item_key       TEXT        NOT NULL,
    tracker        TEXT        NOT NULL,
    item_id        TEXT,
    item_url       TEXT,
    status         TEXT,
    updated_at     TIMESTAMPTZ NOT NULL,
    claimed_by     TEXT,
    claimed_until  TIMESTAMPTZ,
    PRIMARY KEY (repository, project_slice, fingerprint)
);
CREATE UNIQUE INDEX work_item_link_key ON work_item_link (item_key);

-- How far each egress consumer has read in the transition log.
CREATE TABLE egress_checkpoint (
    name      TEXT PRIMARY KEY,
    last_seq  BIGINT NOT NULL
);
