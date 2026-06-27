import Keycloak from 'keycloak-js'
import { config } from '@/config'
import { useAuthStore } from '@/stores/auth'

let keycloak: Keycloak | null = null

export async function initKeycloak(): Promise<boolean> {
    const authStore = useAuthStore()

    if (!config.keycloak.authEnabled) {
        console.warn('KEYCLOAK AUTHENTICATION IS DISABLED LOCALLY! Bypassing login...')
        authStore.setFallbackUser(config.user.id, config.user.name)
        return true
    }

    const url = config.keycloak.url
    const realm = config.keycloak.realm
    const clientId = config.keycloak.clientId

    if (!url || !realm || !clientId) {
        console.warn('Keycloak configuration is missing. Bypassing authentication.')
        authStore.setFallbackUser(config.user.id, config.user.name)
        return true
    }

    keycloak = new Keycloak({
        url,
        realm,
        clientId
    })

    try {
        const authenticated = await keycloak.init({
            onLoad: 'login-required',
            checkLoginIframe: true,
            pkceMethod: 'S256'
        })

        const authStore = useAuthStore()
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