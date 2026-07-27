// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest'
import { config } from '../config'

/**
 * Regression guard for the runtime auth-flag drop (mocked unit tier — no live cluster).
 *
 * `config.keycloak.authEnabled` used to read ONLY `import.meta.env.VITE_AUTH_ENABLED`, which
 * Vite inlines at BUILD time. A deployment setting `auth.enabled: false` therefore could not
 * reach the running SPA: the value did arrive in the container's environment (and in the
 * rendered ConfigMap), but `generate-env.sh` never wrote it into `window.APP_CONFIG` and the
 * bundle carried a frozen default of "enabled". Landing then tried to authenticate against a
 * Keycloak client that need not exist, and `main.ts` mounts only once authenticated — so the
 * page rendered blank with nothing but a keycloak-js timeout in the console.
 *
 * These tests pin the two properties that failure needed: the flag is read at RUNTIME, and an
 * absent flag means auth is OFF (matching Iris, and matching what every deployment configures).
 */
describe('landing auth config', () => {
  beforeEach(() => {
    delete (window as { APP_CONFIG?: unknown }).APP_CONFIG
  })

  it('is disabled when the runtime config says "false" — the case that used to be ignored', () => {
    window.APP_CONFIG = { AUTH_ENABLED: 'false', KEYCLOAK_URL: 'https://kc.example', KEYCLOAK_REALM: 'kantheon', KEYCLOAK_CLIENT_ID: 'landing' }
    expect(config.keycloak.authEnabled).toBe(false)
  })

  it('is enabled only when the runtime config says exactly "true"', () => {
    window.APP_CONFIG = { AUTH_ENABLED: 'true', KEYCLOAK_URL: 'https://kc.example', KEYCLOAK_REALM: 'kantheon', KEYCLOAK_CLIENT_ID: 'landing' }
    expect(config.keycloak.authEnabled).toBe(true)
  })

  it('defaults to disabled when the flag is absent or empty', () => {
    expect(config.keycloak.authEnabled).toBe(false)

    window.APP_CONFIG = { AUTH_ENABLED: '', KEYCLOAK_URL: '', KEYCLOAK_REALM: '', KEYCLOAK_CLIENT_ID: '' }
    expect(config.keycloak.authEnabled).toBe(false)
  })

  it('does not treat a non-"true" value as enabled', () => {
    for (const v of ['1', 'yes', 'TRUE', 'enabled']) {
      window.APP_CONFIG = { AUTH_ENABLED: v, KEYCLOAK_URL: '', KEYCLOAK_REALM: '', KEYCLOAK_CLIENT_ID: '' }
      expect(config.keycloak.authEnabled).toBe(false)
    }
  })
})
