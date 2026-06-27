import type { ReconcileDiff, ReconcileDiffKind } from '@/api/types'

export const DIFF_KINDS: ReconcileDiffKind[] = ['RECON_SYSTEM_ONLY', 'RECON_STATEMENT_ONLY', 'RECON_VALUE_MISMATCH']

/** A stable identifier for the decision endpoint (prefers explicit ids, else synthesised). */
export function diffKeyOf(diff: ReconcileDiff, index: number): string {
  return (
    diff.diffId ??
    diff.diffKey ??
    diff.systemTransaction?.transactionId ??
    diff.statementTransaction?.transactionId ??
    `idx-${index}`
  )
}

/** A diff is "open" until a decision lands (anything other than RECON_OPEN is decided). */
export function isOpen(diff: ReconcileDiff): boolean {
  return !diff.status || diff.status === 'RECON_OPEN'
}

export interface ReconcileGroups {
  byKind: Record<ReconcileDiffKind, ReconcileDiff[]>
  openCount: number
  total: number
}

/** Group diffs by kind and count the still-open ones (drives the summary widget). */
export function groupDiffs(diffs: ReconcileDiff[], openOnly = false): ReconcileGroups {
  const visible = openOnly ? diffs.filter(isOpen) : diffs
  const byKind = { RECON_SYSTEM_ONLY: [], RECON_STATEMENT_ONLY: [], RECON_VALUE_MISMATCH: [] } as Record<
    ReconcileDiffKind,
    ReconcileDiff[]
  >
  for (const d of visible) if (d.kind) byKind[d.kind].push(d)
  return { byKind, openCount: diffs.filter(isOpen).length, total: diffs.length }
}
