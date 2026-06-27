-- Midas-core MV refresh strategy (Stage 1.4). Authority: contracts §6.3.
--
-- v1 mechanism: midas-core refreshes mv_position_current SYNCHRONOUSLY after each
-- write request (one refresh per request — batch inserts coalesce naturally).
-- The MV is owned by the BYPASSRLS role `midas_mv_owner` (init job), so midas_app
-- (a member) can invoke `REFRESH MATERIALIZED VIEW mv_position_current` and the
-- refresh reads across tenants. See V0002's RLS NOTE.
--
-- The NOTIFY trigger below is installed for the FUTURE async path (a debounced
-- PGNotificationListener) documented in contracts §6.3; it is harmless when no
-- listener is attached (notifications with no listener are dropped). The async
-- listener + debounce is deferred to v1.x in favour of the simpler synchronous
-- refresh (which already meets the "fresh within 5s of insert" contract).

CREATE OR REPLACE FUNCTION transactions_notify_refresh() RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('mv_position_refresh', COALESCE(NEW.tenant_id::text, ''));
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- FOR EACH STATEMENT: one notification per insert statement (a batch insert
-- coalesces to a single refresh signal), not one per row.
CREATE TRIGGER trg_transactions_notify_refresh
  AFTER INSERT ON transactions
  FOR EACH STATEMENT EXECUTE FUNCTION transactions_notify_refresh();

-- NOTE: the `midas_mv_owner` BYPASSRLS role (which the MVs above are ALTERed to)
-- is provisioned by the init job (deployment/local/postgres/init-sql-configmap),
-- as the superuser — midas_app cannot grant itself BYPASSRLS. midas-core fails
-- open (warn-and-continue) if the refresh errors, so a DB without the init still
-- serves reads (the MV is just stale).
