import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { bff } from '@/api/client'
import type { ReconcileResponse, ReconcileStatus } from '@/api/types'

export interface ReconcileInput {
  portfolio_id: string
  loader_run_id?: string
  period_start?: string
  period_end?: string
}

/** Run reconciliation for a portfolio/period (sync `POST /midas/reconcile`). */
export function useReconcile() {
  return useMutation({
    mutationFn: (input: ReconcileInput) => bff<ReconcileResponse>('/midas/reconcile', { method: 'POST', body: input }),
  })
}

/** Persist a per-diff decision (mark expected/investigate/resolved); data unchanged. */
export function useDecideDiff() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { diffId: string; status: ReconcileStatus; note?: string }) =>
      bff(`/midas/reconcile/${args.diffId}/decision`, {
        method: 'POST',
        body: { status: args.status, note: args.note },
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reconcile'] }),
  })
}
