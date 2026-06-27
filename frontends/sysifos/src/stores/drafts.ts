import { defineStore } from 'pinia'
import { ref } from 'vue'

export type DraftLifecycle = 'PENDING' | 'COMMITTING' | 'COMMITTED' | 'REJECTED'

export interface FieldError {
  field?: string
  code?: string
  message?: string
  rowIndex?: number
}

export type BatchRowOutcome = 'BR_COMMITTED' | 'BR_SKIPPED' | 'BR_FAILED'
export interface BatchRowState {
  outcome: BatchRowOutcome
  transactionId?: string
  message?: string
}

export interface DraftState {
  status: DraftLifecycle
  artifactRef?: string
  committedCount?: number
  skippedCount?: number
  reason?: string
  errors?: FieldError[]
  /** Per-row outcomes for a DRAFT_TRANSACTION_BATCH, keyed by grid row index (S3). */
  rows?: Record<number, BatchRowState>
}

/**
 * Tracks the lifecycle of async drafts (bulk/import + the Stage 1.3 DRAFT_CLIENT
 * proof) as `SysifosStreamEvent`s arrive on `/stream`. Screens read `byId[draftId]`
 * to surface ack/commit/reject status without polling.
 */
export const useDraftsStore = defineStore('drafts', () => {
  const byId = ref<Record<string, DraftState>>({})

  function onAck(draftId: string) {
    byId.value[draftId] = { ...(byId.value[draftId] ?? { status: 'PENDING' }), status: 'COMMITTING' }
  }

  function onCommitted(c: { draftId?: string; artifactRef?: string; committedCount?: number; skippedCount?: number }) {
    if (!c.draftId) return
    byId.value[c.draftId] = {
      ...(byId.value[c.draftId] ?? { status: 'PENDING' }), // keep per-row results streamed before commit
      status: 'COMMITTED',
      artifactRef: c.artifactRef,
      committedCount: c.committedCount,
      skippedCount: c.skippedCount,
    }
  }

  function onRejected(r: { draftId?: string; reason?: string; errors?: FieldError[] }) {
    if (!r.draftId) return
    byId.value[r.draftId] = {
      ...(byId.value[r.draftId] ?? { status: 'PENDING' }),
      status: 'REJECTED',
      reason: r.reason,
      errors: r.errors ?? [],
    }
  }

  function onBatchRow(b: { draftId?: string; rowIndex?: number; outcome?: BatchRowOutcome; transactionId?: string; message?: string }) {
    if (!b.draftId || b.rowIndex === undefined) return
    const cur = byId.value[b.draftId] ?? { status: 'COMMITTING' as DraftLifecycle }
    const rows = { ...cur.rows }
    rows[b.rowIndex] = { outcome: b.outcome ?? 'BR_COMMITTED', transactionId: b.transactionId, message: b.message }
    byId.value[b.draftId] = { ...cur, rows }
  }

  return { byId, onAck, onCommitted, onRejected, onBatchRow }
})
