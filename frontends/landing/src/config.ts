interface AppConfig {
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
        get authEnabled(): boolean {
            return import.meta.env.VITE_AUTH_ENABLED !== 'false'
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
