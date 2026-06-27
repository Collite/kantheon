// Client-side chart fallback: derive a ChartIntent from raw table rows when the
// backend supplied none (the "Show as graph" icon on a table result). Tolerant
// of numeric *strings* and blank cells. Pure functions so they're unit-testable
// without mounting the renderer.
import type { ChartIntent } from '@/types/envelope'

/** Parse a cell to a finite number, or null (empty / non-numeric → null). */
export function toNumber(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  if (typeof v === 'string') {
    const s = v.trim()
    if (s === '') return null
    const n = Number(s)
    return Number.isFinite(n) ? n : null
  }
  return null
}

/** True when every *present* (non-blank) cell of `key` parses to a number. */
export function isNumericColumn(data: Record<string, unknown>[], key: string): boolean {
  let present = 0
  let numeric = 0
  for (const row of data) {
    const v = row[key]
    if (v === null || v === undefined || (typeof v === 'string' && v.trim() === '')) continue
    present++
    if (toNumber(v) !== null) numeric++
  }
  return present > 0 && numeric === present
}

/** Sum of the numeric values in `key` across `rows`. Null/blank/non-numeric
 *  cells are skipped. Returns a number (0 over an empty/all-blank set). */
export function sumNumericColumn(
  rows: Record<string, unknown>[],
  key: string,
): number {
  let acc = 0
  for (const row of rows) {
    const n = toNumber(row[key])
    if (n !== null) acc += n
  }
  return acc
}

/** Descriptive statistics for one column over the supplied rows. */
export interface ColumnStats {
  /** Rows considered (the sample held client-side, not the full result). */
  rows: number
  /** Cells that are null / undefined / blank string. */
  empty: number
  /** Cells that parsed to a finite number — the basis for sum/avg/min/max. */
  count: number
  sum: number
  /** Null when no numeric cell was present. */
  min: number | null
  max: number | null
  avg: number | null
}

/** Compute count/empty/sum/min/max/avg for `key` over `rows`. Blank cells feed
 *  `empty`; only finite-numeric cells feed the numeric aggregates. */
export function columnStats(rows: Record<string, unknown>[], key: string): ColumnStats {
  let empty = 0
  let count = 0
  let sum = 0
  let min: number | null = null
  let max: number | null = null
  for (const row of rows) {
    const v = row[key]
    if (v === null || v === undefined || (typeof v === 'string' && v.trim() === '')) {
      empty++
      continue
    }
    const n = toNumber(v)
    if (n === null) continue
    count++
    sum += n
    if (min === null || n < min) min = n
    if (max === null || n > max) max = n
  }
  return { rows: rows.length, empty, count, sum, min, max, avg: count > 0 ? sum / count : null }
}

/**
 * A chart intent derived purely from the rows. x = the first non-numeric column
 * (else the first column, e.g. a period code like "2026.01" that itself parses
 * as a number); y = the remaining numeric columns. Returns null when there's
 * nothing chartable (no rows, or no numeric series distinct from x).
 */
export function deriveAutoIntent(rows: Record<string, unknown>[]): ChartIntent | null {
  if (rows.length === 0) return null
  const keys = Object.keys(rows[0] ?? {})
  if (keys.length === 0) return null
  const numericCols = keys.filter((k) => isNumericColumn(rows, k))
  if (numericCols.length === 0) return null
  const xCol = keys.find((k) => !numericCols.includes(k)) ?? keys[0]
  if (xCol === undefined) return null
  const yCols = numericCols.filter((k) => k !== xCol)
  if (yCols.length === 0) return null
  return { kind: 'line', x: xCol, y: yCols, hideSeries: [] }
}

/**
 * Project rows down to the intent's x + numeric series, coercing series cells to
 * numbers so blank/non-numeric cells become gaps (null) rather than NaN. The x
 * column keeps its original value so labels like "2026.10" don't lose a trailing
 * zero to numeric coercion.
 */
export function projectChartData(
  rows: Record<string, unknown>[],
  intent: ChartIntent,
): Record<string, unknown>[] {
  return rows.map((r) => {
    const out: Record<string, unknown> = { [intent.x]: r[intent.x] }
    for (const y of intent.y) out[y] = toNumber(r[y])
    return out
  })
}
