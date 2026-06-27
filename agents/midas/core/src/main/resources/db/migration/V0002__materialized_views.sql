-- Midas-core derived state (Stage 1.4). Authority: contracts §6.2.
--
-- RLS NOTE: these views aggregate the RLS-FORCED `transactions` table ACROSS all
-- tenants (each row carries tenant_id; queries filter by it). A REFRESH runs the
-- view's query as the VIEW OWNER, so it must see every tenant's rows — which
-- midas_app cannot (FORCE RLS applies to it). Therefore the views are created
-- WITH NO DATA (no query at creation, avoiding the tenant guard) and their
-- ownership is transferred to `midas_mv_owner`, a BYPASSRLS role provisioned in
-- the init job (deployment/local/postgres). midas_app is a member of that role, so
-- it may invoke REFRESH; the refresh then reads across tenants. midas-core
-- refreshes synchronously after each write (naturally coalesced per request).
-- Queries against the MV must filter by tenant_id (MVs cannot carry RLS).

-- ===================================================================
-- mv_position_current — net quantity per holding (raw aggregate; FIFO avg cost
-- + valuation are computed in midas-core's calc module, not in the view)
-- ===================================================================
CREATE MATERIALIZED VIEW mv_position_current AS
WITH net AS (
  SELECT
    portfolio_id,
    asset_id,
    tenant_id,
    SUM(
      CASE kind
        WHEN 'BUY' THEN quantity
        WHEN 'SELL' THEN -quantity
        WHEN 'TRANSFER_IN' THEN quantity
        WHEN 'TRANSFER_OUT' THEN -quantity
        WHEN 'ADJUSTMENT' THEN quantity
        WHEN 'CASH_CREDIT' THEN quantity      -- S2: cash legs net onto the CASH asset's position
        WHEN 'CASH_DEBIT' THEN -quantity
        ELSE 0
      END
    ) AS quantity
  FROM transactions
  GROUP BY portfolio_id, asset_id, tenant_id
)
SELECT
  net.portfolio_id,
  net.asset_id,
  net.tenant_id,
  net.quantity,
  NOW() AS as_of
FROM net
WHERE net.quantity <> 0
WITH NO DATA;

CREATE UNIQUE INDEX idx_mv_position_current
  ON mv_position_current (tenant_id, portfolio_id, asset_id);

-- Transfer to the BYPASSRLS owner so REFRESH reads across tenants (see RLS NOTE).
ALTER MATERIALIZED VIEW mv_position_current OWNER TO midas_mv_owner;

-- ===================================================================
-- mv_portfolio_value_daily — daily portfolio value at base ccy
--   (skeleton; total_value_base = 0 until the price source is wired in V0004)
-- ===================================================================
CREATE MATERIALIZED VIEW mv_portfolio_value_daily AS
SELECT
  p.portfolio_id,
  p.tenant_id,
  d::date AS as_of,
  0::numeric(20,4) AS total_value_base
FROM portfolios p
CROSS JOIN generate_series(
  COALESCE(p.inception_date, CURRENT_DATE - INTERVAL '1 year'),
  CURRENT_DATE,
  '1 day'::interval
) d
WITH NO DATA;

CREATE UNIQUE INDEX idx_mv_portfolio_value_daily
  ON mv_portfolio_value_daily (tenant_id, portfolio_id, as_of);

ALTER MATERIALIZED VIEW mv_portfolio_value_daily OWNER TO midas_mv_owner;

-- mv_realised_pnl_ytd — defined in V0004 alongside the calc spec.
