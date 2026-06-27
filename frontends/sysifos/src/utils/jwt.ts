/** Decode a JWT payload (no signature check — the BFF verifies; the FE only reads claims). */
export interface JwtClaims {
  sub?: string
  exp?: number
  realm_access?: { roles?: string[] }
  [key: string]: unknown
}

export function decodeJwt(token: string): JwtClaims | null {
  const parts = token.split('.')
  const payload = parts[1]
  if (!payload) return null
  try {
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    return JSON.parse(json) as JwtClaims
  } catch {
    return null
  }
}

/** Mint a dev bearer (decode-mode; the BFF runs with signature verification off locally). */
export function devBearer(sub: string, tenant: string, roles: string[] = ['midas:write']): string {
  const b64url = (s: string) => btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  const payload = b64url(
    JSON.stringify({ sub, tenant, realm_access: { roles }, exp: Math.floor(Date.now() / 1000) + 3600 }),
  )
  return `h.${payload}.s`
}
