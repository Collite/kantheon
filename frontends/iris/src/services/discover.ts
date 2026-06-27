// Discovery client (Iris Phase 4 Stage 4.3 — PD-7). GET the role-filtered domain
// cards ("what can I ask about ERP, HR, Investment…") for the first-run /
// empty-session panel + suggested-question chips.
import { config } from '@/config'
import { useAuthStore } from '@/stores/auth'

export interface DomainCard {
  agentId: string
  displayName: string
  blurb: string
  exampleQuestions: string[]
}

const getHeaders = async (): Promise<HeadersInit> => {
  const authStore = useAuthStore()
  const headers: Record<string, string> = {}
  if (authStore.userId) headers['X-User-ID'] = authStore.userId
  if (authStore.isAuthenticated) {
    await authStore.updateToken(5)
    if (authStore.token) headers['Authorization'] = `Bearer ${authStore.token}`
  }
  return headers
}

export const discoverApi = {
  /** GET /v1/discover → role-filtered domain cards (empty on any failure). */
  async domains(): Promise<DomainCard[]> {
    try {
      const res = await fetch(`${config.bff.baseUrl}/v1/discover`, { headers: await getHeaders() })
      if (!res.ok) return []
      const body = (await res.json()) as { domains: DomainCard[] }
      return body.domains ?? []
    } catch {
      return []
    }
  },
}
