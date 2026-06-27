/**
 * Reads a VITE_* configuration value with runtime-first precedence:
 *   1. `window.APP_CONFIG` — injected at container start by
 *      `scripts/generate-env.sh` (the K8s/Docker deployment path).
 *   2. `import.meta.env` — inlined by Vite at build time (the `vite dev`
 *      and `vite build` path).
 *
 * An empty string is treated as "not set" at each level so a blank value
 * (e.g. an intentionally empty port) falls through to the next source and,
 * ultimately, to the caller's default. Returns `undefined` when unset
 * everywhere, preserving the existing `!== undefined` / `|| default` logic.
 */
function env(key: string): string | undefined {
  const runtime = (typeof window !== 'undefined' ? window.APP_CONFIG : undefined) as
    | Record<string, string | undefined>
    | undefined
  const fromRuntime = runtime?.[key]
  const raw =
    fromRuntime !== undefined && fromRuntime !== ''
      ? fromRuntime
      : (import.meta.env as Record<string, string | undefined>)[key]
  return raw === '' ? undefined : raw
}

export const config = {
  user: {
    // Used as the X-User-ID / displayed user when Keycloak auth is disabled
    // (VITE_AUTH_ENABLED !== 'true') or a Keycloak init fails. When Keycloak
    // IS active, the identity comes from the ID token and these values are
    // ignored.
    get id(): string {
      return env('VITE_USER_ID') || 'analyst'
    },
    get name(): string {
      return env('VITE_USER_NAME') || 'Analyst'
    },
  },
  keycloak: {
    get authEnabled(): boolean {
      return env('VITE_AUTH_ENABLED') === 'true'
    },
    get url(): string {
      return env('VITE_KEYCLOAK_URL') || 'http://localhost:8080'
    },
    get realm(): string {
      return env('VITE_KEYCLOAK_REALM') || 'df-test'
    },
    get clientId(): string {
      return env('VITE_KEYCLOAK_CLIENT_ID') || 'agents-fe'
    }
  },
  otel: {
    get enabled(): boolean {
      return env('VITE_TELEMETRY_ENABLED') === 'true'
    },
    get host(): string {
      return env('VITE_OTEL_EXPORTER_OTLP_HOST') || 'localhost'
    },
    get httpPort(): string {
      return env('VITE_OTEL_EXPORTER_OTLP_HTTP_PORT') || '4318'
    },
    get httpsPort(): string {
      return env('VITE_OTEL_EXPORTER_OTLP_HTTPS_PORT') || '4318'
    },
    get grpcPort(): string {
      return env('VITE_OTEL_EXPORTER_OTLP_GRPC_PORT') || '4317'
    },
    get protocol(): string {
      return env('VITE_AGENTS_FE_OTEL_PROTOCOL') || 'http'
    }
  },
  llmGateway: {
    get host(): string {
      const envHost = env('VITE_LLM_GTW_SERVER')
      return envHost !== undefined ? envHost : 'localhost'
    },
    get port(): string {
      const envPort = env('VITE_LLM_GTW_SERVER_PORT')
      const envHost = env('VITE_LLM_GTW_SERVER')
      if (envHost !== undefined) {
        return envPort !== undefined ? envPort : ''
      }
      return envPort !== undefined ? envPort : '7101'
    },
    get path(): string {
      return env('VITE_LLM_GTW_SERVER_PATH') || ''
    },
    get protocol(): string {
      return env('VITE_LLM_GTW_SERVER_PROTOCOL') || 'https'
    },
    get baseUrl() {
      let path: string = this.protocol + '://' + this.host
      if (this.port) {
        path += ':' + this.port
      }
      if (this.path) {
        path += this.path.startsWith('/') ? this.path : '/' + this.path
      }
      return path
    }
  },
  bff: {
    // Single backend origin for the whole FE (Iris Phase 2 Stage 2.2 re-point).
    // The /v1/* routes are appended by services/irisStream.ts.
    get baseUrl(): string {
      return env('VITE_BFF_BASE_URL') || 'http://localhost:7410'
    },
  },
  // Legacy / unused by default now — the BFF (above) is the backend. Kept so the
  // unwired-but-retained Golem-direct code paths still resolve a config object.
  golem: {
    get host(): string {
      const envHost = env('VITE_GOLEM_SERVER')
      return envHost !== undefined ? envHost : 'localhost'
    },
    get port(): string {
      const envPort = env('VITE_GOLEM_SERVER_PORT')
      const envHost = env('VITE_GOLEM_SERVER')
      if (envHost !== undefined) {
        return envPort !== undefined ? envPort : ''
      }
      return envPort !== undefined ? envPort : '7903'
    },
    get protocol(): string {
      return env('VITE_GOLEM_SERVER_PROTOCOL') || 'http'
    },
    get baseUrl() {
      let path: string = this.protocol + '://' + this.host
      if (this.port) {
        path += ':' + this.port
      }
      return path
    }
  },
  /**
   * The list of selectable Golem agents. Every agent is the same Golem service
   * with a different package set loaded; only host (and optionally port / path /
   * protocol) differ. Populated from `VITE_GOLEM_AGENTS` — a JSON array of
   * `{ id, label, host, port?, path?, protocol? }`, where any omitted field
   * inherits the shared `VITE_GOLEM_SERVER_*` value. When the var is unset (or
   * unparseable) it falls back to a single agent built from the legacy
   * `VITE_GOLEM_SERVER*` vars, so existing single-agent deployments keep working.
   */
  get golemAgents(): GolemAgent[] {
    return resolveGolemAgents()
  },
  erpMcp: {
    get host(): string {
      const envHost = env('VITE_MCP_ERP_SERVER')
      return envHost !== undefined ? envHost : 'localhost'
    },
    get port(): string {
      const envPort = env('VITE_MCP_ERP_SERVER_PORT')
      const envHost = env('VITE_MCP_ERP_SERVER')
      if (envHost !== undefined) {
        return envPort !== undefined ? envPort : ''
      }
      return envPort !== undefined ? envPort : '7151'
    },
    get path(): string {
      return env('VITE_MCP_ERP_SERVER_PATH') || ''
    },
    get protocol(): string {
      return env('VITE_MCP_ERP_SERVER_PROTOCOL') || 'http'
    },
    get baseUrl() {
      let path: string = this.protocol + '://' + this.host
      if (this.port) {
        path += ':' + this.port
      }
      if (this.path) {
        path += this.path.startsWith('/') ? this.path : '/' + this.path
      }
      return path
    }
  },
  fuzzyMcp: {
    get host(): string {
      const envHost = env('VITE_MCP_FUZZY_SERVER')
      return envHost !== undefined ? envHost : 'localhost'
    },
    get port(): string {
      const envPort = env('VITE_MCP_FUZZY_SERVER_PORT')
      const envHost = env('VITE_MCP_FUZZY_SERVER')
      if (envHost !== undefined) {
        return envPort !== undefined ? envPort : ''
      }
      return envPort !== undefined ? envPort : '7152'
    },
    get path(): string {
      return env('VITE_MCP_FUZZY_SERVER_PATH') || ''
    },
    get protocol(): string {
      return env('VITE_MCP_FUZZY_SERVER_PROTOCOL') || 'http'
    },
    get baseUrl() {
      let path: string = this.protocol + '://' + this.host
      if (this.port) {
        path += ':' + this.port
      }
      if (this.path) {
        path += this.path.startsWith('/') ? this.path : '/' + this.path
      }
      return path
    }
  },
  metaMcp: {
    get host(): string {
      const envHost = env('VITE_MCP_METADATA_SERVER')
      return envHost !== undefined ? envHost : 'localhost'
    },
    get port(): string {
      const envPort = env('VITE_MCP_METADATA_SERVER_PORT')
      const envHost = env('VITE_MCP_METADATA_SERVER')
      if (envHost !== undefined) {
        return envPort !== undefined ? envPort : ''
      }
      return envPort !== undefined ? envPort : '7153'
    },
    get path(): string {
      return env('VITE_MCP_METADATA_SERVER_PATH') || ''
    },
    get protocol(): string {
      return env('VITE_MCP_METADATA_SERVER_PROTOCOL') || 'http'
    },
    get baseUrl() {
      let path: string = this.protocol + '://' + this.host
      if (this.port) {
        path += ':' + this.port
      }
      if (this.path) {
        path += this.path.startsWith('/') ? this.path : '/' + this.path
      }
      return path
    }
  },
  localMetaMcp: {
    get host(): string {
      const envHost = env('VITE_MCP_LOCAL_METADATA_SERVER')
      return envHost !== undefined ? envHost : 'localhost'
    },
    get port(): string {
      const envPort = env('VITE_MCP_LOCAL_METADATA_SERVER_PORT')
      const envHost = env('VITE_MCP_LOCAL_METADATA_SERVER')
      if (envHost !== undefined) {
        return envPort !== undefined ? envPort : ''
      }
      return envPort !== undefined ? envPort : '7199'
    },
    get path(): string {
      return env('VITE_MCP_LOCAL_METADATA_SERVER_PATH') || ''
    },
    get protocol(): string {
      return env('VITE_MCP_LOCAL_METADATA_SERVER_PROTOCOL') || 'http'
    },
    get baseUrl() {
      let path: string = this.protocol + '://' + this.host
      if (this.port) {
        path += ':' + this.port
      }
      if (this.path) {
        path += this.path.startsWith('/') ? this.path : '/' + this.path
      }
      return path
    }
  },
  table: {
    // Rows per page in result tables. Runtime-overridable via
    // VITE_TABLE_PAGE_SIZE (window.APP_CONFIG); falls back to 25. A non-numeric
    // or non-positive value is ignored so a bad override can't break paging.
    get pageSize(): number {
      const n = Number(env('VITE_TABLE_PAGE_SIZE'))
      return Number.isFinite(n) && n >= 1 ? Math.floor(n) : 25
    },
  },
}

/** A resolved Golem agent entry, ready for the agents menu + endpoint lookup. */
export interface GolemAgent {
  /** Stable id used in the route (`/agents/:id`) and as the localStorage key. */
  id: string
  /** Human-readable name shown in the Agents menu and the view header. */
  label: string
  /** Origin (protocol://host[:port]) — the v2 routes are appended by agentService. */
  baseUrl: string
}

/** One entry as authored in the `VITE_GOLEM_AGENTS` JSON array. */
interface GolemAgentSpec {
  id?: string
  label?: string
  host?: string
  port?: string
  path?: string
  protocol?: string
}

/**
 * Build the origin for one agent. Each field falls back to the shared
 * `VITE_GOLEM_SERVER_*` default when the spec omits it, encoding the
 * "only host (and maybe port) differ between agents" assumption.
 */
function buildGolemBaseUrl(spec: GolemAgentSpec): string {
  const protocol = spec.protocol || env('VITE_GOLEM_SERVER_PROTOCOL') || 'http'
  const host = spec.host || env('VITE_GOLEM_SERVER') || 'localhost'
  const port = spec.port !== undefined ? spec.port : env('VITE_GOLEM_SERVER_PORT')
  let url = protocol + '://' + host
  if (port) {
    url += ':' + port
  }
  return url
}

function resolveGolemAgents(): GolemAgent[] {
  const raw = env('VITE_GOLEM_AGENTS')
  if (raw) {
    try {
      const specs = JSON.parse(raw) as GolemAgentSpec[]
      if (Array.isArray(specs)) {
        const agents = specs
          .filter((s): s is GolemAgentSpec => !!s && !!s.host)
          .map((s) => ({
            id: String(s.id ?? s.host),
            label: String(s.label ?? s.id ?? s.host),
            baseUrl: buildGolemBaseUrl(s),
          }))
        if (agents.length > 0) {
          return agents
        }
      }
      console.warn('[config] VITE_GOLEM_AGENTS yielded no usable agents (each needs a host); using single-agent fallback')
    } catch (e) {
      console.warn('[config] VITE_GOLEM_AGENTS is not valid JSON; using single-agent fallback', e)
    }
  }
  // Single-agent fallback — the BFF is the one backend (Iris Phase 2 Stage 2.2
  // re-point). The VITE_GOLEM_AGENTS JSON override above still allows multi-agent.
  return [{ id: 'iris', label: 'Iris', baseUrl: config.bff.baseUrl }]
}
