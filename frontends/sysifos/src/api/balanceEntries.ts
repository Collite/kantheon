import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { type Ref, unref } from 'vue'
import { bff } from '@/api/client'
import type { BalanceEntryCommitResponse, BalanceEntryPreview, ListTransactionsResponse } from '@/api/types'

export interface BalanceEntryInput {
  portfolio_id: string
  asset_id: string
  target_quantity: string
  as_of: string
  reason?: string
}

/** Read-only preview of the derived ADJUSTMENT (sync `POST /midas/balance-entries:preview`). */
export function usePreviewBalanceEntry() {
  return useMutation({
    mutationFn: (input: BalanceEntryInput) =>
      bff<BalanceEntryPreview>('/midas/balance-entries:preview', { method: 'POST', body: input }),
  })
}

/** Commit — Midas-core re-runs the diff server-side for race safety, then inserts. */
export function useCommitBalanceEntry() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (input: BalanceEntryInput) =>
      bff<BalanceEntryCommitResponse>('/midas/balance-entries:commit', { method: 'POST', body: input }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['transactions-screen'] }),
  })
}

export interface BalanceHistoryParams {
  portfolio_id?: string
  asset_id?: string
}

/** Prior balance entries = ADJUSTMENT-kind transactions for the portfolio/asset (T5). */
export function useBalanceHistory(params: Ref<BalanceHistoryParams>) {
  return useQuery({
    queryKey: ['balance-history', params],
    queryFn: () =>
      bff<ListTransactionsResponse>('/midas/transactions', {
        query: { ...unref(params), kind: 'TX_ADJUSTMENT', size: 100 },
      }),
    enabled: () => !!unref(params).portfolio_id,
  })
}
