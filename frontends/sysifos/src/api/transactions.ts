import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { type Ref, unref } from 'vue'
import { bff } from '@/api/client'
import type { EditTransactionResponse, Transaction, TransactionResponse, TransactionsScreen } from '@/api/types'

export interface TransactionScreenParams {
  portfolio_id?: string
  asset_id?: string
  from?: string
  to?: string
  kind?: string
  page?: number
  size?: number
}

/**
 * The assembled Transactions screen (BFF fan-out §3.4): the page of security legs
 * with their derived cash legs nested, plus the asset + portfolio dictionaries.
 * Disabled until a portfolio is chosen (the screen is per-portfolio).
 */
export function useTransactionsScreen(params: Ref<TransactionScreenParams>) {
  return useQuery({
    queryKey: ['transactions-screen', params],
    queryFn: () => bff<TransactionsScreen>('/screens/transactions', { query: { ...unref(params) } }),
    enabled: () => !!unref(params).portfolio_id,
  })
}

/** Single manual entry — security leg only; Midas-core derives the cash legs. */
export function useCreateTransaction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (transaction: Transaction) =>
      bff<TransactionResponse>('/midas/transactions', { method: 'POST', body: { transaction } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['transactions-screen'] }),
  })
}

/** Inline edit = reverse + replace (contracts §2.4); cash legs follow the reversal. */
export function useEditTransaction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { id: string; newTransaction: Transaction; reason?: string }) =>
      bff<EditTransactionResponse>(`/midas/transactions/${args.id}`, {
        method: 'PATCH',
        body: { transactionId: args.id, newTransaction: args.newTransaction, reason: args.reason },
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['transactions-screen'] }),
  })
}
