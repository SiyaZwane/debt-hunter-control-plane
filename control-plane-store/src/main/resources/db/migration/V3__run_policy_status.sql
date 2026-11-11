-- The policy outcome a run reported, so analytics can measure how often policy would have failed.
ALTER TABLE run ADD COLUMN policy_status TEXT;
