// Hand-written views of the midas/v1 REST shapes the screens consume (proto-JSON,
// camelCase). Narrower than the generated bindings on purpose — only the fields
// the UI reads/writes.

export type ClientStatus = 'CLIENT_ACTIVE' | 'CLIENT_ARCHIVED'
export type PortfolioStatus = 'PORTFOLIO_ACTIVE' | 'PORTFOLIO_ARCHIVED'
export type PortfolioType = 'PORTFOLIO_BROKERAGE' | 'PORTFOLIO_RETIREMENT' | 'PORTFOLIO_OTHER'

export interface Client {
  clientId?: string
  name: string
  contactEmail?: string
  contactPhone?: string
  status?: ClientStatus
}

export interface Portfolio {
  portfolioId?: string
  clientId: string
  name: string
  baseCurrency: string
  portfolioType?: PortfolioType
  costBasisMethod?: string
  inceptionDate?: string
  trackCash?: boolean
  status?: PortfolioStatus
}

export type AssetKind = 'ASSET_STOCK' | 'ASSET_ETF' | 'ASSET_BOND' | 'ASSET_FUND' | 'ASSET_CASH'
export type AssetStatus = 'ASSET_ACTIVE' | 'ASSET_DELISTED'

export interface Asset {
  assetId?: string
  symbol: string
  isin?: string
  name: string
  kind: AssetKind
  exchange?: string
  currency: string
  status?: AssetStatus
}

export type TransactionKind =
  | 'TX_BUY'
  | 'TX_SELL'
  | 'TX_DIVIDEND'
  | 'TX_INTEREST'
  | 'TX_FEE'
  | 'TX_TAX'
  | 'TX_TRANSFER_IN'
  | 'TX_TRANSFER_OUT'
  | 'TX_ADJUSTMENT'
  | 'TX_CASH_CREDIT'
  | 'TX_CASH_DEBIT'

export type TransactionSource =
  | 'TX_SRC_MANUAL'
  | 'TX_SRC_LOADER_EXCEL'
  | 'TX_SRC_LOADER_GOOGLE_FINANCE'
  | 'TX_SRC_LOADER_API'
  | 'TX_SRC_DERIVATION'
  | 'TX_SRC_REVERSAL'

export interface Money {
  amount: string
  currency?: string
}

export interface Transaction {
  transactionId?: string
  portfolioId: string
  assetId: string
  kind: TransactionKind
  tradeDate?: string
  settleDate?: string
  quantity: string
  price?: Money
  fee?: Money
  total?: Money
  currency: string
  reversesTransactionId?: string
  note?: string
  source?: TransactionSource
  correlationId?: string
}

/** A security leg with its derived cash legs nested (assembled by the BFF §3.4). */
export interface TransactionRow extends Transaction {
  cashLegs?: Transaction[]
}

export interface PageInfo {
  page: number
  size: number
  total: number
}

export interface ListAssetsResponse {
  assets?: Asset[]
  pageInfo?: PageInfo
}
export interface AssetResponse {
  asset?: Asset
}

/** BFF `/screens/transactions` fan-out shape (§3.4). */
export interface TransactionsScreen {
  transactions?: TransactionRow[]
  assets?: Asset[]
  portfolios?: Portfolio[]
  pageInfo?: PageInfo
}

export interface TransactionResponse {
  transaction?: Transaction
}

export interface ListTransactionsResponse {
  transactions?: Transaction[]
  pageInfo?: PageInfo
}

export interface BalanceEntryPreview {
  portfolioId?: string
  assetId?: string
  currentQuantity?: string
  targetQuantity?: string
  diffQuantity?: string
  proposedTransaction?: Transaction
}
export interface BalanceEntryCommitResponse {
  committedTransaction?: Transaction
}

export type LoaderRunStatus =
  | 'LR_UPLOADED'
  | 'LR_PARSING'
  | 'LR_MAPPING'
  | 'LR_PREVIEW_READY'
  | 'LR_COMMITTING'
  | 'LR_COMPLETED'
  | 'LR_FAILED'

export interface LoaderRun {
  loaderRunId?: string
  sourceKind?: string
  brokerId?: string
  portfolioId?: string
  status?: LoaderRunStatus
  uploadedAt?: string
  rowCountTotal?: number
  rowCountCommitted?: number
  rowCountSkipped?: number
  rowCountFailed?: number
}

export type PreviewDecision = 'PV_NEW' | 'PV_DUPLICATE' | 'PV_ERROR'
export interface PreviewRow {
  sourceRowIndex?: number
  draft?: Transaction
  decision?: PreviewDecision
  note?: string
}

/** BFF `/screens/import/{id}` fan-out shape (§3.4). */
export interface ImportScreen {
  loaderRun?: LoaderRun
  rows?: PreviewRow[]
  summary?: { newCount?: number; duplicateCount?: number; errorCount?: number }
}

export interface UploadAccepted {
  loaderRunId?: string
  statusUrl?: string
}

export interface ListLoaderRunsResponse {
  runs?: LoaderRun[]
  pageInfo?: PageInfo
}

export type ReconcileDiffKind = 'RECON_SYSTEM_ONLY' | 'RECON_STATEMENT_ONLY' | 'RECON_VALUE_MISMATCH'
export type ReconcileStatus = 'RECON_OPEN' | 'RECON_EXPECTED' | 'RECON_INVESTIGATE' | 'RECON_RESOLVED'

export interface FieldDelta {
  field?: string
  systemValue?: string
  statementValue?: string
}
export interface ReconcileDiff {
  diffId?: string
  diffKey?: string
  kind?: ReconcileDiffKind
  systemTransaction?: Transaction
  statementTransaction?: Transaction
  deltas?: FieldDelta[]
  status?: ReconcileStatus
}
export interface ReconcileSummary {
  totalDiffs?: number
  systemOnly?: number
  statementOnly?: number
  valueMismatch?: number
}
export interface ReconcileResponse {
  diffs?: ReconcileDiff[]
  summary?: ReconcileSummary
}

export type AuditOperation = 'CREATE' | 'UPDATE' | 'ARCHIVE' | 'REVERSE' | 'DELETE'
export interface AuditEntry {
  auditId?: string
  actorUserId?: string
  entityType?: string
  entityId?: string
  operation?: AuditOperation
  beforeJsonb?: unknown
  afterJsonb?: unknown
  occurredAt?: string
  traceId?: string
}
export interface ListAuditResponse {
  entries?: AuditEntry[]
  pageInfo?: PageInfo
}
/** Midas edit = reversal + replacement (contracts §2.4). */
export interface EditTransactionResponse {
  reversal?: Transaction
  replacement?: Transaction
}

export interface ListClientsResponse {
  clients?: Client[]
  pageInfo?: PageInfo
}
export interface ClientResponse {
  client?: Client
}
export interface ListPortfoliosResponse {
  portfolios?: Portfolio[]
  pageInfo?: PageInfo
}
export interface PortfolioResponse {
  portfolio?: Portfolio
}
