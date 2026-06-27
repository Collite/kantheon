import { useQuery } from '@tanstack/vue-query'
import { type Ref, unref } from 'vue'
import { bff } from '@/api/client'
import { config } from '@/config'
import { useSessionStore } from '@/stores/session'
import { submitDraft } from '@/api/drafts'
import type { ImportScreen, ListLoaderRunsResponse, LoaderRun, LoaderRunStatus, UploadAccepted } from '@/api/types'

/**
 * Upload an Excel statement (contracts §3.5). Multipart so the file rides as-is;
 * the BFF proxies it to the loader, which returns the ad-hoc (non-proto) body
 * `{ loader_run_id, status_url }` verbatim — snake_case, unlike the proto-JSON
 * screens. Map it to the camelCase `UploadAccepted` the rest of the FE reads. Uses
 * raw fetch (not the JSON `bff()` wrapper) to send `FormData`.
 */
export async function uploadStatement(file: File, brokerId: string, portfolioId: string): Promise<UploadAccepted> {
  const session = useSessionStore()
  const body = new FormData()
  body.append('file', file)
  body.append('broker_id', brokerId)
  body.append('portfolio_id', portfolioId)
  const res = await fetch(`${config.bffBase}/loaders/excel/uploads`, {
    method: 'POST',
    headers: session.bearer ? { Authorization: `Bearer ${session.bearer}` } : {},
    body,
  })
  if (!res.ok) throw new Error(`upload failed: ${res.status}`)
  const raw = (await res.json()) as Record<string, string | undefined>
  return { loaderRunId: raw.loader_run_id ?? raw.loaderRunId, statusUrl: raw.status_url ?? raw.statusUrl }
}

/** The assembled Import preview (BFF fan-out §3.4): the run + diffed preview rows. */
export function useImportScreen(loaderRunId: Ref<string | undefined>) {
  return useQuery({
    queryKey: ['import-screen', loaderRunId],
    queryFn: () => bff<ImportScreen>(`/screens/import/${unref(loaderRunId)}`),
    enabled: () => !!unref(loaderRunId),
  })
}

export interface LoaderRunsParams {
  portfolio_id?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

/** Past loader runs for the history tab (T7). */
export function useLoaderRuns(params: Ref<LoaderRunsParams>) {
  return useQuery({
    queryKey: ['loader-runs', params],
    queryFn: () => bff<ListLoaderRunsResponse>('/loaders/excel/runs', { query: { ...unref(params) } }),
  })
}

/** Run statuses past which there is nothing left to poll. */
const TERMINAL_RUN_STATUSES: LoaderRunStatus[] = ['LR_PREVIEW_READY', 'LR_COMPLETED', 'LR_FAILED']

/**
 * Poll a loader run's status (contracts §3.5). The loader does not push progress
 * onto the session SSE bus in v1, so the Import screen drives its progress bar and
 * preview-ready transition from this poll instead. Stops once the run reaches a
 * terminal state (preview ready / completed / failed).
 */
export function useLoaderRunStatus(loaderRunId: Ref<string | null>) {
  return useQuery({
    queryKey: ['loader-run-status', loaderRunId],
    queryFn: () => bff<LoaderRun>(`/loaders/excel/runs/${unref(loaderRunId)}`),
    enabled: () => !!unref(loaderRunId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && TERMINAL_RUN_STATUSES.includes(status) ? false : 1500
    },
  })
}

/** Commit a previewed run via the async draft path (`DRAFT_LOADER_RUN_COMMIT`). */
export function commitLoaderRun(loaderRunId: string, skipExisting = true) {
  return submitDraft('DRAFT_LOADER_RUN_COMMIT', { loaderRunId, skipExisting })
}
