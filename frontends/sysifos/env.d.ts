/// <reference types="vite/client" />

interface ImportMetaEnv {
  // BFF origin proxied under /bff in dev (vite.config.ts). Overridable per env.
  readonly VITE_BFF_BASE_URL?: string
  readonly VITE_SERVER_PORT?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/**
 * Runtime configuration injected at container start (Stage 1.3 nginx entrypoint).
 * Optional because it is absent until that script runs.
 */
interface Window {
  APP_CONFIG?: Record<string, string | undefined>
}
