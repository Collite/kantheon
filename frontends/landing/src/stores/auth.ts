import { ref } from 'vue'
import { defineStore } from 'pinia'
import type Keycloak from 'keycloak-js'
import type { KeycloakTokenParsed } from 'keycloak-js'

export const useAuthStore = defineStore('auth', () => {
    const isAuthenticated = ref(false)
    const user = ref<KeycloakTokenParsed | null>(null)
    const token = ref<string | null>(null)

    let keycloakInstance: Keycloak | null = null

    function setKeycloak(kc: Keycloak) {
        keycloakInstance = kc
        _updateState(kc)
    }

    function _updateState(kc: Keycloak) {
        isAuthenticated.value = !!kc.authenticated
        token.value = kc.token || null
        if (kc.idTokenParsed) {
            user.value = kc.idTokenParsed
        }
    }

    async function updateToken(minValidity = 30) {
        if (!keycloakInstance) return false
        try {
            const refreshed = await keycloakInstance.updateToken(minValidity)
            if (refreshed) {
                _updateState(keycloakInstance)
            }
            return true
        } catch (e) {
            console.error('Failed to refresh Keycloak token', e)
            logout()
            return false
        }
    }

    function login() {
        if (keycloakInstance) {
            keycloakInstance.login()
        }
    }

    function logout(redirectUri?: string) {
        if (keycloakInstance) {
            keycloakInstance.logout({
                redirectUri: redirectUri || window.location.origin
            })
        }
    }

    return {
        isAuthenticated,
        user,
        token,
        setKeycloak,
        updateToken,
        login,
        logout
    }
})
