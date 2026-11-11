-- When a run was received and how much history it had, so coverage tracking can tell a repository
-- that has gone silent, or whose analysis is shallow, from one that is healthy.
ALTER TABLE run ADD COLUMN received_at TIMESTAMPTZ;
ALTER TABLE run ADD COLUMN history_depth TEXT;
CREATE INDEX run_repository_recent ON run (repository, applied_seq DESC);
