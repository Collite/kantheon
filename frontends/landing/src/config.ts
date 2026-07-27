interface AppConfig {
    AUTH_ENABLED?: string;
    KEYCLOAK_URL: string;
    KEYCLOAK_REALM: string;
    KEYCLOAK_CLIENT_ID: string;
    GRAFANA_DASHBOARD_URL?: string;
    LINK_AGENT?: string;
    LINK_SERVICES?: string;
    LINK_DEV_PORTAL?: string;
    LINK_GRAFANA?: string;
    LINK_ARGOCD?: string;
    LINK_TRAEFIK?: string;
    LINK_KEYCLOAK?: string;
    HEALTH_URL?: string;
}

declare global {
    interface Window {
        APP_CONFIG?: AppConfig;
    }
}

export const config = {
    keycloak: {
        // Runtime-first, like every other setting below. This USED to read only
        // `import.meta.env`, which Vite inlines at BUILD time — so a deployment setting
        // `auth.enabled: false` had no way to reach the running SPA: the value arrived in the
        // container's environment, generate-env.sh never wrote it into window.APP_CONFIG, and
        // the built bundle carried a frozen default. The page then tried to authenticate
        // against a Keycloak client that need not exist, and main.ts only mounts once
        // authenticated — so the whole app silently rendered blank.
        //
        // Polarity now matches Iris (`=== 'true'`, i.e. default OFF) rather than the old
        // `!== 'false'` (default ON). Landing is a public link page — an unset flag meaning
        // "no login" is the safer default, and it matches what every deployment already
        // configures explicitly.
        get authEnabled(): boolean {
            return (window.APP_CONFIG?.AUTH_ENABLED || import.meta.env.VITE_AUTH_ENABLED) === 'true'
        },
        get url(): string {
            return window.APP_CONFIG?.KEYCLOAK_URL || import.meta.env.VITE_KEYCLOAK_URL || ''
        },
        get realm(): string {
            return window.APP_CONFIG?.KEYCLOAK_REALM || import.meta.env.VITE_KEYCLOAK_REALM || ''
        },
        get clientId(): string {
            return window.APP_CONFIG?.KEYCLOAK_CLIENT_ID || import.meta.env.VITE_KEYCLOAK_CLIENT_ID || ''
        }
    },
    get grafanaDashboardUrl(): string {
        return window.APP_CONFIG?.GRAFANA_DASHBOARD_URL || import.meta.env.VITE_GRAFANA_DASHBOARD_URL || ''
    },
    // Base URL of the health-check service (e.g. https://health.kantheon.example).
    // Empty falls back to same-origin, so the dashboard hits `/health/all/detailed`.
    get healthUrl(): string {
        return window.APP_CONFIG?.HEALTH_URL || import.meta.env.VITE_HEALTH_URL || ''
    },
    links: {
        get agent(): string { return window.APP_CONFIG?.LINK_AGENT || import.meta.env.VITE_LINK_AGENT || '' },
        get services(): string { return window.APP_CONFIG?.LINK_SERVICES || import.meta.env.VITE_LINK_SERVICES || '' },
        get devPortal(): string { return window.APP_CONFIG?.LINK_DEV_PORTAL || import.meta.env.VITE_LINK_DEV_PORTAL || '' },
        get grafana(): string { return window.APP_CONFIG?.LINK_GRAFANA || import.meta.env.VITE_LINK_GRAFANA || '' },
        get argocd(): string { return window.APP_CONFIG?.LINK_ARGOCD || import.meta.env.VITE_LINK_ARGOCD || '' },
        get traefik(): string { return window.APP_CONFIG?.LINK_TRAEFIK || import.meta.env.VITE_LINK_TRAEFIK || '' },
        get keycloak(): string { return window.APP_CONFIG?.LINK_KEYCLOAK || import.meta.env.VITE_LINK_KEYCLOAK || '' }
    }
};
