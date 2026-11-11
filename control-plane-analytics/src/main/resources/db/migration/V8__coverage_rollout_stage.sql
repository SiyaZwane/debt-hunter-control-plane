-- Each repository's rollout stage as it was when coverage was last reconciled; NULL if the
-- repository is not in the rollout.
ALTER TABLE rm_coverage ADD COLUMN rollout_stage INTEGER;
