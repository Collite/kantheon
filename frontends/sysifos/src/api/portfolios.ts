import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { type Ref, unref } from 'vue'
import { bff } from '@/api/client'
import type { Portfolio, PortfolioResponse, ListPortfoliosResponse } from '@/api/types'

export interface PortfolioListParams {
  page?: number
  size?: number
  status?: string
  client_id?: string
}

export function usePortfolios(params: Ref<PortfolioListParams>) {
  return useQuery({
    queryKey: ['portfolios', params],
    queryFn: () => bff<ListPortfoliosResponse>('/midas/portfolios', { query: { ...unref(params) } }),
  })
}

export function useCreatePortfolio() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (portfolio: Portfolio) =>
      bff<PortfolioResponse>('/midas/portfolios', { method: 'POST', body: { portfolio } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['portfolios'] }),
  })
}

export function useUpdatePortfolio() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (portfolio: Portfolio) =>
      bff<PortfolioResponse>(`/midas/portfolios/${portfolio.portfolioId}`, { method: 'PATCH', body: { portfolio } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['portfolios'] }),
  })
}

export function useArchivePortfolio() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (portfolioId: string) =>
      bff<PortfolioResponse>(`/midas/portfolios/${portfolioId}/archive`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['portfolios'] }),
  })
}
