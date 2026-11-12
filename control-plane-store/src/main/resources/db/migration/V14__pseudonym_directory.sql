-- Which person each pseudonym stands for. The identity is sealed (AES-256-GCM, its own key); the
-- pseudonym is part of the seal, so a row cannot be moved onto another pseudonym. Nothing else in
-- the schema references this table, and it is never purged.
CREATE TABLE pseudonym_directory (
    pseudonym       TEXT        PRIMARY KEY,
    sealed_identity BYTEA       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
