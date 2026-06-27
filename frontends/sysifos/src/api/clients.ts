import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query'
import { type Ref, unref } from 'vue'
import { bff } from '@/api/client'
import type { Client, ClientResponse, ListClientsResponse } from '@/api/types'

export interface ClientListParams {
  page?: number
  size?: number
  status?: string
  name_prefix?: string
}

/** Server-side-paged client list (sync read via the CRUD proxy). */
export function useClients(params: Ref<ClientListParams>) {
  return useQuery({
    queryKey: ['clients', params],
    queryFn: () => bff<ListClientsResponse>('/midas/clients', { query: { ...unref(params) } }),
  })
}

export function useCreateClient() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (client: Client) => bff<ClientResponse>('/midas/clients', { method: 'POST', body: { client } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['clients'] }),
  })
}

export function useUpdateClient() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (client: Client) =>
      bff<ClientResponse>(`/midas/clients/${client.clientId}`, { method: 'PATCH', body: { client } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['clients'] }),
  })
}

export function useArchiveClient() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (clientId: string) => bff<ClientResponse>(`/midas/clients/${clientId}/archive`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['clients'] }),
  })
}
