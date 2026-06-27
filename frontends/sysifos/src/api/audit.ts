import { useQuery } from '@tanstack/vue-query'
import { type Ref, unref } from 'vue'
import { bff } from '@/api/client'
import type { ListAuditResponse } from '@/api/types'

export interface AuditParams {
  entity_type?: string
  actor_user_id?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

/**
 * Audit log (admin-only; T6). Read through the CRUD proxy from a Midas-core read
 * endpoint (`GET /api/v1/audit` with the same filters). The route is gated
 * `midas:admin` FE-side and by Midas-core authz.
 */
export function useAudit(params: Ref<AuditParams>) {
  return useQuery({
    queryKey: ['audit', params],
    queryFn: () => bff<ListAuditResponse>('/midas/audit', { query: { size: 100, ...unref(params) } }),
  })
}
