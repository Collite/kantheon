import Keycloak from 'keycloak-js'
import { config } from '../config'
import { useAuthStore } from '../stores/auth'

let keycloak: Keycloak | null = null

export async function initKeycloak(): Promise<boolean> {
    const authStore = useAuthStore()

    if (!config.keycloak.authEnabled) {
        console.warn('KEYCLOAK AUTHENTICATION IS DISABLED LOCALLY! Bypassing login...')
        authStore.user = {
            name: 'Local Developer',
            preferred_username: 'dev',
            email: 'dev@localhost'
        }
        authStore.isAuthenticated = true
        return true
    }

    const url = config.keycloak.url
    const realm = config.keycloak.realm
    const clientId = config.keycloak.clientId

    if (!url || !realm || !clientId) {
        console.warn('Keycloak configuration is missing. Authentication cannot be initialized.')
        return false
    }

    keycloak = new Keycloak({
        url,
        realm,
        clientId
    })

    try {
        const authenticated = await keycloak.init({
            // "login-required" forces redirect to Keycloak immediately since it's a secured landing page
            onLoad: 'login-required',
            // Disabled deliberately. keycloak-js's session-check iframe is loaded from the
            // Keycloak origin, so it depends on third-party cookies and on that origin's
            // certificate being trusted by the browser. When either is unavailable the iframe
            // never posts back and init fails with "Timeout when waiting for 3rd party check
            // iframe message" — which reads like a Keycloak outage but is a browser policy.
            // With `login-required` the redirect flow already establishes the session; the
            // iframe only adds passive single-logout detection, which is not worth a hard
            // dependency on third-party cookies that browsers are actively removing.
            checkLoginIframe: false,
            pkceMethod: 'S256'
        })

        authStore.setKeycloak(keycloak)

        return authenticated
    } catch (error) {
        console.error('Failed to initialize Keycloak', error)
        return false
    }
}

export function getKeycloakInstance(): Keycloak | null {
    return keycloak
}
