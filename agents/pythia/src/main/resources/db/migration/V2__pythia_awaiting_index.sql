-- The AWAITING_* TTL sweeper runs frequently and scans only parked rows past their
-- expiry. A partial index on awaiting_ttl_until (NULL for non-parked rows, which are
-- the vast majority) keeps that sweep off a full-table scan as the inbox grows.
CREATE INDEX IF NOT EXISTS pythia_investigations_awaiting_ttl
    ON pythia_investigations (awaiting_ttl_until)
    WHERE awaiting_ttl_until IS NOT NULL;
