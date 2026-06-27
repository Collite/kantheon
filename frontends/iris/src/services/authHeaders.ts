// Shared request-header builder for golem (and other gateway-fronted) calls.
//
// Attaches the Keycloak bearer when authenticated so requests pass the Envoy
// Gateway JWT policy, plus X-User-ID for trace/RLS. Async because `updateToken`
// may refresh an expiring token before we read it. Mirrors the pattern in
// agentService.getHeaders() / mcpClient.ts — kept in ONE place so every golem
// caller stays consistent (regression guard: /metadata/queries, the healthz
// poll, and /v2/agent/graph all 401'd because they each rolled their own
// header builder and forgot the bearer).
import { useAuthStore } from '@/stores/auth'

export const authHeaders = async (
  extra: Record<string, string> = {},
): Promise<Record<string, string>> => {
  const auth = useAuthStore()
  const headers: Record<string, string> = { ...extra }
  if (auth.userId) headers['X-User-ID'] = auth.userId
  if (auth.isAuthenticated) {
    await auth.updateToken(5)
    if (auth.token) headers['Authorization'] = `Bearer ${auth.token}`
  }
  return headers
}
