// Phase 4 — FE client for `/metadata/queries`.
//
// Single round-trip per fetch — the BE aggregates across local +
// platform metadata MCPs and returns the merged catalog with any
// per-source warnings inline.
import { config } from '@/config'
import { authHeaders } from '@/services/authHeaders'
import type { QueriesResponse, QuerySourceFilter } from '@/types/queries'

const baseUrl = (): string => {
  let url =
    (config as { golem: { baseUrl?: string } }).golem.baseUrl ||
    'erp-agent.dfpartner.cz'
  if (!url.startsWith('http')) {
    url = `${window.location.protocol}//${url}`
  }
  return url
}

export const fetchQueries = async (
  source: QuerySourceFilter = 'both',
): Promise<QueriesResponse> => {
  const url = new URL(`${baseUrl()}/metadata/queries`)
  url.searchParams.set('source', source)
  const r = await fetch(url.toString(), { headers: await authHeaders() })
  if (!r.ok) {
    throw new Error(`/metadata/queries failed: HTTP ${r.status}`)
  }
  return (await r.json()) as QueriesResponse
}
