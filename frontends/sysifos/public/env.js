// Default (no-op) runtime config for `vite dev` / `vite build`. In the container
// this is OVERWRITTEN at start by scripts/generate-env.sh, which substitutes the
// real VITE_* env vars. Empty here so the app falls back to import.meta.env (dev)
// then to the in-code defaults in src/config/index.ts.
window.APP_CONFIG = {
  VITE_BFF_BASE: '',
  VITE_AUTH_ENABLED: '',
  VITE_KEYCLOAK_URL: '',
  VITE_KEYCLOAK_REALM: '',
  VITE_KEYCLOAK_CLIENT_ID: '',
  VITE_TENANT_CLAIM: '',
}
