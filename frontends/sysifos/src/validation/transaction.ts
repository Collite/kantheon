import { TransactionFormSchema } from '@/validation/generated'
import type { Transaction } from '@/api/types'

/** A single editable grid cell → a `TransactionForm` field. */
export type GridField = 'symbol' | 'kind' | 'tradeDate' | 'quantity' | 'price' | 'fee' | 'currency'

export const GRID_FIELDS: GridField[] = ['symbol', 'kind', 'tradeDate', 'quantity', 'price', 'fee', 'currency']

/** One grid row as edited in the bulk grid (symbol is resolved to an assetId on commit). */
export interface GridRow {
  symbol: string
  kind: string
  tradeDate: string
  quantity: string
  price: string
  fee: string
  currency: string
  /** Resolved from `symbol` against the asset dictionary; '' until known. */
  assetId?: string
}

export function emptyRow(currency = 'CZK'): GridRow {
  return { symbol: '', kind: 'TX_BUY', tradeDate: '', quantity: '', price: '', fee: '', currency }
}

const DECIMAL = /^-?\d+(\.\d+)?$/
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

/** Header tokens (en/cs, case-insensitive) → grid field, for paste header detection. */
const HEADER_ALIASES: Record<string, GridField> = {
  symbol: 'symbol',
  ticker: 'symbol',
  aktivum: 'symbol',
  kind: 'kind',
  type: 'kind',
  druh: 'kind',
  date: 'tradeDate',
  tradedate: 'tradeDate',
  datum: 'tradeDate',
  quantity: 'quantity',
  qty: 'quantity',
  mnozstvi: 'quantity',
  price: 'price',
  cena: 'price',
  fee: 'fee',
  poplatek: 'fee',
  currency: 'currency',
  ccy: 'currency',
  mena: 'currency',
}

function norm(s: string): string {
  return s
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
}

/**
 * Parse a pasted multi-row/multi-col TSV block into grid rows. If the first line
 * looks like a header (every cell maps to a known column), columns are mapped by
 * name; otherwise the positional order [[GRID_FIELDS]] is used. Short rows are
 * padded; extra columns are dropped. Blank lines are skipped.
 */
export function parsePastedBlock(text: string, currency = 'CZK'): GridRow[] {
  const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0)
  if (lines.length === 0) return []

  const firstCells = lines[0]!.split('\t').map(norm)
  const isHeader = firstCells.length > 1 && firstCells.every((c) => c in HEADER_ALIASES)
  const order: GridField[] = isHeader ? firstCells.map((c) => HEADER_ALIASES[c]!) : GRID_FIELDS
  const body = isHeader ? lines.slice(1) : lines

  return body.map((line) => {
    const cells = line.split('\t')
    const row = emptyRow(currency)
    order.forEach((field, i) => {
      const v = cells[i]?.trim()
      if (v !== undefined && v !== '') row[field] = v
    })
    return row
  })
}

/**
 * Per-cell validation. Reuses the generated `TransactionFormSchema` field shapes
 * where they map 1:1 (quantity, price, currency); symbol/kind/date carry grid-local
 * rules (symbol must resolve to a known asset — that's a quick-create trigger, not
 * a hard error here). Returns a message or `null` when the cell is valid.
 */
export function validateCell(field: GridField, value: string): string | null {
  switch (field) {
    case 'symbol':
      return value.trim() ? null : 'required'
    case 'kind':
      return value ? null : 'required'
    case 'tradeDate':
      return ISO_DATE.test(value) ? null : 'YYYY-MM-DD'
    case 'quantity':
      if (!DECIMAL.test(value)) return 'number'
      return parseFloat(value) !== 0 ? null : 'must not be 0'
    case 'price':
      if (!DECIMAL.test(value)) return 'number'
      return parseFloat(value) >= 0 ? null : '>= 0'
    case 'fee':
      if (!value) return null
      if (!DECIMAL.test(value)) return 'number'
      return parseFloat(value) >= 0 ? null : '>= 0'
    case 'currency':
      return /^[A-Z]{3}$/.test(value) ? null : 'ISO 4217'
  }
}

/** True when every required cell of the row passes (symbol-resolution checked separately). */
export function rowValid(row: GridRow): boolean {
  return GRID_FIELDS.every((f) => validateCell(f, row[f]) === null)
}

/** Map a validated grid row → a `TransactionForm` proto-JSON object (security leg). */
export function rowToForm(row: GridRow, portfolioId: string): Transaction {
  const parsed = TransactionFormSchema.safeParse({
    portfolio_id: portfolioId,
    asset_id: row.assetId ?? '',
    kind: row.kind,
    trade_date: row.tradeDate,
    quantity: row.quantity,
    price: { amount: row.price },
    fee: { amount: row.fee || undefined },
    currency: row.currency,
  })
  if (!parsed.success) throw new Error('row failed schema')
  return {
    portfolioId,
    assetId: row.assetId ?? '',
    kind: row.kind as Transaction['kind'],
    tradeDate: `${row.tradeDate}T00:00:00Z`,
    quantity: row.quantity,
    price: { amount: row.price },
    ...(row.fee ? { fee: { amount: row.fee } } : {}),
    currency: row.currency,
  }
}
