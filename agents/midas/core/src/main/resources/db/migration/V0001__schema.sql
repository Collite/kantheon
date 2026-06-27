-- Midas-core baseline schema (Stage 1.3). Authority: docs/architecture/midas/
-- contracts.md §6.1 (+ §1.1.A cash-leg baseline). Owner role: midas_app.
--
-- RLS NOTE: every tenant table is ENABLE + FORCE ROW LEVEL SECURITY. FORCE is
-- required because midas_app OWNS these tables (it ran this migration) and a
-- table owner BYPASSES RLS by default — without FORCE the tenant policies would
-- not apply to the app's own connections and cross-tenant isolation would fail.
-- (The contract DDL omits FORCE; added here so RlsLeakageComponentSpec passes.)

-- ===================================================================
-- Extensions
-- ===================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;     -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ===================================================================
-- Helper for RLS: read tenant from session var; throw if unset
-- ===================================================================
CREATE OR REPLACE FUNCTION app_current_tenant() RETURNS UUID AS $$
BEGIN
  RETURN current_setting('app.tenant_id')::uuid;
EXCEPTION WHEN OTHERS THEN
  RAISE EXCEPTION 'app.tenant_id session var not set';
END;
$$ LANGUAGE plpgsql STABLE;

-- ===================================================================
-- clients
-- ===================================================================
CREATE TABLE clients (
  client_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          UUID NOT NULL,
  name               TEXT NOT NULL,
  contact_email      TEXT,
  contact_phone      TEXT,
  status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by_user_id TEXT NOT NULL,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_by_user_id TEXT NOT NULL
);
CREATE INDEX idx_clients_tenant_name ON clients (tenant_id, name);
ALTER TABLE clients ENABLE ROW LEVEL SECURITY;
ALTER TABLE clients FORCE ROW LEVEL SECURITY;
CREATE POLICY clients_tenant ON clients USING (tenant_id = app_current_tenant());

-- ===================================================================
-- portfolios
-- ===================================================================
CREATE TABLE portfolios (
  portfolio_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID NOT NULL,
  client_id           UUID NOT NULL REFERENCES clients(client_id),
  name                TEXT NOT NULL,
  base_currency       CHAR(3) NOT NULL,
  portfolio_type      TEXT NOT NULL DEFAULT 'BROKERAGE'
                      CHECK (portfolio_type IN ('BROKERAGE','RETIREMENT','OTHER')),
  cost_basis_method   TEXT NOT NULL DEFAULT 'FIFO' CHECK (cost_basis_method IN ('FIFO')),
  inception_date      DATE,
  status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  track_cash          BOOLEAN NOT NULL DEFAULT true,   -- S2: derive the cash counter-leg of each trade
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by_user_id  TEXT NOT NULL,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_by_user_id  TEXT NOT NULL
);
CREATE INDEX idx_portfolios_tenant_client ON portfolios (tenant_id, client_id);
ALTER TABLE portfolios ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolios FORCE ROW LEVEL SECURITY;
CREATE POLICY portfolios_tenant ON portfolios USING (tenant_id = app_current_tenant());

-- ===================================================================
-- assets
-- ===================================================================
CREATE TABLE assets (
  asset_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID,                                   -- NULL for global assets (rare in v1)
  symbol              TEXT NOT NULL,
  isin                TEXT,
  name                TEXT NOT NULL,
  kind                TEXT NOT NULL
                      CHECK (kind IN ('STOCK','ETF','BOND','FUND','CASH')),
  exchange            TEXT,
  currency            CHAR(3) NOT NULL,
  status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELISTED')),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by_user_id  TEXT NOT NULL,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_by_user_id  TEXT NOT NULL,
  UNIQUE (tenant_id, symbol, exchange)
);
CREATE INDEX idx_assets_symbol ON assets (symbol);
ALTER TABLE assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE assets FORCE ROW LEVEL SECURITY;
-- USING allows READING global (tenant_id IS NULL) assets + the caller's own; the
-- explicit WITH CHECK forbids WRITING a global or cross-tenant row (otherwise WITH
-- CHECK defaults to USING and any tenant could insert a globally-visible asset).
CREATE POLICY assets_tenant ON assets
  USING (tenant_id IS NULL OR tenant_id = app_current_tenant())
  WITH CHECK (tenant_id = app_current_tenant());

-- ===================================================================
-- transactions (event log; append-only)
-- ===================================================================
CREATE TABLE transactions (
  transaction_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id               UUID NOT NULL,
  portfolio_id            UUID NOT NULL REFERENCES portfolios(portfolio_id),
  asset_id                UUID NOT NULL REFERENCES assets(asset_id),
  kind                    TEXT NOT NULL
                          CHECK (kind IN ('BUY','SELL','DIVIDEND','INTEREST','FEE','TAX',
                                          'TRANSFER_IN','TRANSFER_OUT','ADJUSTMENT',
                                          'CASH_CREDIT','CASH_DEBIT')),    -- S2 derived cash legs
  trade_date              TIMESTAMPTZ NOT NULL,
  settle_date             TIMESTAMPTZ,
  quantity                NUMERIC(28,8) NOT NULL,
  price_amount            NUMERIC(20,4) NOT NULL DEFAULT 0,
  price_currency          CHAR(3),
  fee_amount              NUMERIC(20,4) NOT NULL DEFAULT 0,
  fee_currency            CHAR(3),
  tax_amount              NUMERIC(20,4) NOT NULL DEFAULT 0,
  tax_currency            CHAR(3),
  total_amount            NUMERIC(20,4) NOT NULL,
  total_currency          CHAR(3) NOT NULL,
  currency                CHAR(3) NOT NULL,
  external_id             TEXT,
  reverses_transaction_id UUID REFERENCES transactions(transaction_id),
  correlation_id          UUID,                          -- S2: links a security leg to its derived cash leg
  note                    TEXT,
  source                  TEXT NOT NULL
                          CHECK (source IN ('MANUAL','LOADER_EXCEL','LOADER_GOOGLE_FINANCE',
                                            'LOADER_API','DERIVATION','REVERSAL')),
  recorded_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  recorded_by_user_id     TEXT NOT NULL
);
CREATE UNIQUE INDEX uq_transactions_tenant_extid
  ON transactions (tenant_id, external_id)
  WHERE external_id IS NOT NULL;
CREATE INDEX idx_transactions_portfolio_trade ON transactions (portfolio_id, trade_date DESC);
CREATE INDEX idx_transactions_asset_trade     ON transactions (asset_id, trade_date DESC);
CREATE INDEX idx_transactions_reverses        ON transactions (reverses_transaction_id) WHERE reverses_transaction_id IS NOT NULL;
CREATE INDEX idx_transactions_correlation     ON transactions (correlation_id) WHERE correlation_id IS NOT NULL;
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions FORCE ROW LEVEL SECURITY;
CREATE POLICY transactions_tenant ON transactions USING (tenant_id = app_current_tenant());

-- Append-only: forbid UPDATE/DELETE on transactions
CREATE OR REPLACE FUNCTION transactions_no_mutate() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'transactions table is append-only; use reversal entries'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_transactions_no_update BEFORE UPDATE ON transactions
  FOR EACH ROW EXECUTE FUNCTION transactions_no_mutate();
CREATE TRIGGER trg_transactions_no_delete BEFORE DELETE ON transactions
  FOR EACH ROW EXECUTE FUNCTION transactions_no_mutate();

-- ===================================================================
-- fx_rates
-- ===================================================================
CREATE TABLE fx_rates (
  from_ccy    CHAR(3) NOT NULL,
  to_ccy      CHAR(3) NOT NULL,
  rate_date   DATE NOT NULL,
  rate        NUMERIC(20,10) NOT NULL,
  source      TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (from_ccy, to_ccy, rate_date)
);
-- fx_rates is globally shared; no tenant_id, no RLS.

-- ===================================================================
-- reconciliation_decisions
-- ===================================================================
CREATE TABLE reconciliation_decisions (
  decision_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL,
  diff_key        TEXT NOT NULL,             -- hash of (loader_run_id, source_row_index, transaction_id)
  loader_run_id   UUID,
  transaction_id  UUID,
  status          TEXT NOT NULL CHECK (status IN ('OPEN','EXPECTED','INVESTIGATE','RESOLVED')),
  note            TEXT,
  decided_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  decided_by_user_id TEXT NOT NULL,
  UNIQUE (tenant_id, diff_key)
);
ALTER TABLE reconciliation_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_decisions FORCE ROW LEVEL SECURITY;
CREATE POLICY recon_tenant ON reconciliation_decisions USING (tenant_id = app_current_tenant());

-- ===================================================================
-- loader_runs (status table; loader-owned)
-- ===================================================================
CREATE TABLE loader_runs (
  loader_run_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL,
  user_id              TEXT NOT NULL,
  source_kind          TEXT NOT NULL,
  broker_id            TEXT,
  portfolio_id         UUID,
  status               TEXT NOT NULL CHECK (status IN
                       ('UPLOADED','PARSING','MAPPING','PREVIEW_READY','COMMITTING','COMPLETED','FAILED')),
  uploaded_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at         TIMESTAMPTZ,
  row_count_total      INT NOT NULL DEFAULT 0,
  row_count_committed  INT NOT NULL DEFAULT 0,
  row_count_skipped    INT NOT NULL DEFAULT 0,
  row_count_failed     INT NOT NULL DEFAULT 0,
  error_summary        TEXT,
  upload_blob_ref      TEXT          -- FS path in v1; S3 URI later
);
CREATE INDEX idx_loader_runs_tenant_status ON loader_runs (tenant_id, status, uploaded_at DESC);
ALTER TABLE loader_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE loader_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY loader_runs_tenant ON loader_runs USING (tenant_id = app_current_tenant());

-- ===================================================================
-- audit_log
-- ===================================================================
CREATE TABLE audit_log (
  audit_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL,
  actor_user_id    TEXT NOT NULL,
  entity_type      TEXT NOT NULL,
  entity_id        UUID NOT NULL,
  operation        TEXT NOT NULL CHECK (operation IN ('CREATE','UPDATE','ARCHIVE','REVERSE','DELETE')),
  before_jsonb     JSONB,
  after_jsonb      JSONB,
  occurred_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  trace_id         TEXT
);
CREATE INDEX idx_audit_log_tenant_occurred ON audit_log (tenant_id, occurred_at DESC);
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_log_tenant ON audit_log USING (tenant_id = app_current_tenant());
