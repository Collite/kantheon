import type { BalanceEntryPreview } from '@/api/types'

export interface BalanceExplanation {
  hasDiff: boolean
  diff: number
  /** e.g. "Current 100 → Target 120 → Adjustment +20 AAPL" */
  text: (symbol: string) => string
}

/**
 * Plain-language reading of a balance-entry preview. `diff == 0` means the position
 * is already at target — nothing to commit (the screen disables Commit and shows a
 * friendly note). The arrow line mirrors design §5.3.
 */
export function explainBalanceEntry(preview: BalanceEntryPreview): BalanceExplanation {
  const current = Number(preview.currentQuantity ?? '0')
  const target = Number(preview.targetQuantity ?? '0')
  const diff = Number(preview.diffQuantity ?? String(target - current))
  const signed = diff > 0 ? `+${diff}` : String(diff)
  return {
    hasDiff: diff !== 0,
    diff,
    text: (symbol: string) => `${current} → ${target} → ${signed} ${symbol}`,
  }
}
