// Artifact client (Iris Phase 4 Stage 4.2 — POST/GET/PATCH/DELETE /v1/artifacts,
// refresh, dashboard open). Bearer (OBO) on every call, same as irisStream.
//
// A pin is a saved, refreshable view of a turn's bubble; a dashboard is an
// ordered pin collection. Refresh re-executes deterministically on the BFF
// (never an LLM call); a failed refresh surfaces `refreshError` on the artifact.
import { config } from '@/config'
import { useAuthStore } from '@/stores/auth'
import type {
  ArtifactDto,
  ArtifactPatch,
  CreateDashboardRequest,
  CreatePinRequest,
} from '@/types/artifacts'

const baseUrl = (): string => config.bff.baseUrl

const getHeaders = async (extra: Record<string, string> = {}): Promise<HeadersInit> => {
  const authStore = useAuthStore()
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...extra }
  if (authStore.userId) headers['X-User-ID'] = authStore.userId
  if (authStore.isAuthenticated) {
    await authStore.updateToken(5)
    if (authStore.token) headers['Authorization'] = `Bearer ${authStore.token}`
  }
  return headers
}

export interface DashboardTile {
  /** A refreshed (or as-stored) member pin. */
  pin: ArtifactDto
}

export const artifactsApi = {
  /** POST /v1/artifacts {kind:pin} — capture a turn's bubble as a refreshable pin. */
  async createPin(req: CreatePinRequest): Promise<ArtifactDto> {
    const res = await fetch(`${baseUrl()}/v1/artifacts`, {
      method: 'POST',
      headers: await getHeaders(),
      body: JSON.stringify({ kind: 'pin', ...req }),
    })
    if (!res.ok) throw new Error(`createPin failed: HTTP ${res.status}`)
    return res.json() as Promise<ArtifactDto>
  },

  /** POST /v1/artifacts {kind:dashboard} — name an ordered pin collection. */
  async createDashboard(req: CreateDashboardRequest): Promise<ArtifactDto> {
    const res = await fetch(`${baseUrl()}/v1/artifacts`, {
      method: 'POST',
      headers: await getHeaders(),
      body: JSON.stringify({ kind: 'dashboard', refreshMode: 'on_open', ...req }),
    })
    if (!res.ok) throw new Error(`createDashboard failed: HTTP ${res.status}`)
    return res.json() as Promise<ArtifactDto>
  },

  /** GET /v1/artifacts[?kind=] — the caller's artifacts (newest first). */
  async list(kind?: 'pin' | 'dashboard'): Promise<ArtifactDto[]> {
    const qs = kind ? `?kind=${kind}` : ''
    const res = await fetch(`${baseUrl()}/v1/artifacts${qs}`, { headers: await getHeaders() })
    if (!res.ok) throw new Error(`listArtifacts failed: HTTP ${res.status}`)
    const body = (await res.json()) as { artifacts: ArtifactDto[] }
    return body.artifacts
  },

  /** GET /v1/artifacts/{id}. */
  async get(id: string): Promise<ArtifactDto> {
    const res = await fetch(`${baseUrl()}/v1/artifacts/${encodeURIComponent(id)}`, {
      headers: await getHeaders(),
    })
    if (!res.ok) throw new Error(`getArtifact failed: HTTP ${res.status}`)
    return res.json() as Promise<ArtifactDto>
  },

  /** PATCH /v1/artifacts/{id} — rename, edit params/layout/members. */
  async patch(id: string, patch: ArtifactPatch): Promise<ArtifactDto> {
    const res = await fetch(`${baseUrl()}/v1/artifacts/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: await getHeaders(),
      body: JSON.stringify(patch),
    })
    if (!res.ok) throw new Error(`patchArtifact failed: HTTP ${res.status}`)
    return res.json() as Promise<ArtifactDto>
  },

  /** DELETE /v1/artifacts/{id}. */
  async remove(id: string): Promise<void> {
    const res = await fetch(`${baseUrl()}/v1/artifacts/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      headers: await getHeaders(),
    })
    if (!res.ok && res.status !== 404) throw new Error(`deleteArtifact failed: HTTP ${res.status}`)
  },

  /** POST /v1/artifacts/{id}/refresh — deterministic re-execution. */
  async refresh(id: string): Promise<ArtifactDto> {
    const res = await fetch(`${baseUrl()}/v1/artifacts/${encodeURIComponent(id)}/refresh`, {
      method: 'POST',
      headers: await getHeaders(),
    })
    if (!res.ok) throw new Error(`refreshArtifact failed: HTTP ${res.status}`)
    return res.json() as Promise<ArtifactDto>
  },

  /** GET /v1/dashboards/{id}/open (SSE) — one `pin` frame per member, then `done`. */
  async openDashboard(id: string, onPin: (pin: ArtifactDto) => void): Promise<void> {
    const res = await fetch(`${baseUrl()}/v1/dashboards/${encodeURIComponent(id)}/open`, {
      headers: await getHeaders({ Accept: 'text/event-stream' }),
    })
    if (!res.ok) throw new Error(`openDashboard failed: HTTP ${res.status}`)
    const reader = res.body?.getReader()
    if (!reader) return
    const decoder = new TextDecoder()
    let buffer = ''
    let event: string | null = null
    const flush = (data: string) => {
      if (event === 'pin' && data) {
        try {
          onPin(JSON.parse(data) as ArtifactDto)
        } catch {
          /* skip a malformed frame */
        }
      }
    }
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        // Flush a final `data:` line that arrived without a trailing newline (it
        // never entered the while-loop below, so the last frame would be lost).
        const tail = buffer.trimEnd()
        if (tail.startsWith('data:')) flush(tail.slice('data:'.length).trim())
        break
      }
      buffer += decoder.decode(value, { stream: true })
      let nl: number
      while ((nl = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, nl).trimEnd()
        buffer = buffer.slice(nl + 1)
        if (line.startsWith('event:')) event = line.slice('event:'.length).trim()
        else if (line.startsWith('data:')) flush(line.slice('data:'.length).trim())
        else if (line === '') event = null
      }
    }
  },
}
