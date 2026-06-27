import { config } from '@/config'
import { useSessionStore } from '@/stores/session'
import { devBearer } from '@/utils/jwt'
import { keycloakLogin } from '@/services/keycloak'

/**
 * Establish the session before the app mounts. With auth enabled, run the
 * Keycloak PKCE flow (login-required → redirect). Locally (default), mint a
 * decode-mode dev bearer so the shell runs without a realm — the BFF runs with
 * signature verification off in the local overlay.
 */
export async function initAuth(): Promise<void> {
  const session = useSessionStore()
  if (!config.auth.enabled) {
    session.setFromToken(devBearer('dev-user', 'acme'))
    return
  }
  const token = await keycloakLogin()
  if (token) session.setFromToken(token)
}
