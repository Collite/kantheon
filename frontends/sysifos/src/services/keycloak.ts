import Keycloak from 'keycloak-js'
import { config } from '@/config'

let kc: Keycloak | null = null

/**
 * Keycloak PKCE login (contracts §3 — the bearer the BFF verifies). Returns the
 * access token, or null if login failed. Only used when `config.auth.enabled`;
 * the local/dev shell mints a decode-mode bearer instead (see services/auth).
 */
export async function keycloakLogin(): Promise<string | null> {
  kc = new Keycloak({ url: config.auth.keycloakUrl, realm: config.auth.realm, clientId: config.auth.clientId })
  const authenticated = await kc.init({ onLoad: 'login-required', pkceMethod: 'S256' })
  return authenticated ? (kc.token ?? null) : null
}

export function keycloakLogout(): void {
  kc?.logout()
}
