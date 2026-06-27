// Runtime config. Reads Vite env (build-time) with a window.APP_CONFIG override
// (container-time, Stage 1.3 T7 nginx entrypoint). Auth defaults OFF so the
// shell runs locally without a Keycloak realm (a dev session is minted instead).
function env(key: string, fallback = ''): string {
  return window.APP_CONFIG?.[key] ?? (import.meta.env[key as keyof ImportMetaEnv] as string | undefined) ?? fallback
}

export const config = {
  // FE→BFF calls go through the dev proxy (/bff) or the nginx location in prod.
  bffBase: env('VITE_BFF_BASE', '/bff'),
  auth: {
    enabled: env('VITE_AUTH_ENABLED', 'false') === 'true',
    keycloakUrl: env('VITE_KEYCLOAK_URL'),
    realm: env('VITE_KEYCLOAK_REALM', 'kantheon'),
    clientId: env('VITE_KEYCLOAK_CLIENT_ID', 'sysifos'),
    tenantClaim: env('VITE_TENANT_CLAIM', 'tenant'),
  },
}
