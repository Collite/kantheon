/// <reference types="vite/client" />

interface ImportMetaEnv {
    readonly VITE_ERP_AGENT_SERVER?: string
    readonly VITE_ERP_AGENT_SERVER_PORT?: string
    readonly VITE_ERP_AGENT_SERVER_PROTOCOL?: string
    // Golem agent(s). The shared VITE_GOLEM_SERVER* values are the defaults each
    // entry in VITE_GOLEM_AGENTS inherits; VITE_GOLEM_AGENTS is a JSON array of
    // { id, label, host, port?, path?, protocol? }.
    readonly VITE_GOLEM_SERVER?: string
    readonly VITE_GOLEM_SERVER_PORT?: string
    readonly VITE_GOLEM_SERVER_PATH?: string
    readonly VITE_GOLEM_SERVER_PROTOCOL?: string
    readonly VITE_GOLEM_AGENTS?: string
}

interface ImportMeta {
    readonly env: ImportMetaEnv
}

/**
 * Runtime configuration injected by /env.js (generated at container start by
 * scripts/generate-env.sh). Keys mirror the VITE_* names read in
 * src/config/index.ts. Optional because it is absent until that script runs.
 */
interface Window {
    APP_CONFIG?: Record<string, string | undefined>
}