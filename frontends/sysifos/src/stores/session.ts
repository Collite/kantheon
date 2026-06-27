import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { config } from '@/config'
import { decodeJwt } from '@/utils/jwt'

/**
 * Session store — the authenticated user, tenant, roles, and bearer derived from
 * the JWT. The bearer rides every BFF call; the BFF re-derives the tenant from
 * the token and forwards it as `X-Tenant-Id` (never trusts an FE-supplied tenant).
 */
export const useSessionStore = defineStore('session', () => {
  const bearer = ref<string | null>(null)
  const userId = ref<string | null>(null)
  const tenantId = ref<string | null>(null)
  const tenants = ref<string[]>([])
  const roles = ref<string[]>([])
  const lastHeartbeatAt = ref<number | null>(null)

  const isAuthenticated = computed(() => bearer.value !== null)
  const hasMultipleTenants = computed(() => tenants.value.length > 1)

  function setFromToken(token: string) {
    const claims = decodeJwt(token)
    if (!claims?.sub) return
    bearer.value = token
    userId.value = claims.sub
    tenantId.value = (claims[config.auth.tenantClaim] as string) ?? 'default'
    roles.value = claims.realm_access?.roles ?? []
    if (tenantId.value && !tenants.value.includes(tenantId.value)) tenants.value = [tenantId.value]
  }

  function switchTenant(tenant: string) {
    if (tenants.value.includes(tenant)) tenantId.value = tenant
  }

  function clear() {
    bearer.value = null
    userId.value = null
    tenantId.value = null
    roles.value = []
  }

  const hasRole = (role: string) => roles.value.includes(role)

  return {
    bearer,
    userId,
    tenantId,
    tenants,
    roles,
    lastHeartbeatAt,
    isAuthenticated,
    hasMultipleTenants,
    setFromToken,
    switchTenant,
    clear,
    hasRole,
  }
})
