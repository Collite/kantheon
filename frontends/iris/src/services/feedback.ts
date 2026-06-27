// Turn feedback client (Iris Phase 4 Stage 4.3 — PD-3). POST a 👍/👎 verdict
// (+ optional reason/comment) for a turn; upserted per (turn, user) on the BFF.
import { config } from '@/config'
import { useAuthStore } from '@/stores/auth'

export type Verdict = 'up' | 'down'
export type FeedbackReason = 'wrong_data' | 'wrong_agent' | 'wrong_format' | 'too_slow' | 'other'

export interface FeedbackResponse {
  turnId: string
  verdict: Verdict
  reason?: FeedbackReason
  correctedAgentId?: string
}

const getHeaders = async (): Promise<HeadersInit> => {
  const authStore = useAuthStore()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (authStore.userId) headers['X-User-ID'] = authStore.userId
  if (authStore.isAuthenticated) {
    await authStore.updateToken(5)
    if (authStore.token) headers['Authorization'] = `Bearer ${authStore.token}`
  }
  return headers
}

export const feedbackApi = {
  /** POST /v1/turns/{turnId}/feedback — record a verdict (upsert per turn,user). */
  async submit(
    turnId: string,
    verdict: Verdict,
    reason?: FeedbackReason,
    comment?: string,
  ): Promise<FeedbackResponse> {
    const res = await fetch(`${config.bff.baseUrl}/v1/turns/${encodeURIComponent(turnId)}/feedback`, {
      method: 'POST',
      headers: await getHeaders(),
      body: JSON.stringify({ verdict, ...(reason ? { reason } : {}), ...(comment ? { comment } : {}) }),
    })
    if (!res.ok) throw new Error(`feedback failed: HTTP ${res.status}`)
    return res.json() as Promise<FeedbackResponse>
  },
}
