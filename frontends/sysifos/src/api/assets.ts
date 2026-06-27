import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { type Ref, unref } from 'vue'
import { bff } from '@/api/client'
import type { Asset, AssetResponse, ListAssetsResponse } from '@/api/types'

export interface AssetListParams {
  page?: number
  size?: number
  symbol?: string
  kind?: string
  exchange?: string
}

/** Server-side-filtered asset list (sync read via the CRUD proxy). */
export function useAssets(params: Ref<AssetListParams>) {
  return useQuery({
    queryKey: ['assets', params],
    queryFn: () => bff<ListAssetsResponse>('/midas/assets', { query: { ...unref(params) } }),
  })
}

export function useCreateAsset() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (asset: Asset) => bff<AssetResponse>('/midas/assets', { method: 'POST', body: { asset } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['assets'] }),
  })
}

export function useUpdateAsset() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (asset: Asset) =>
      bff<AssetResponse>(`/midas/assets/${asset.assetId}`, { method: 'PATCH', body: { asset } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['assets'] }),
  })
}
