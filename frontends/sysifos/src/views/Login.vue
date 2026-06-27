<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { initAuth } from '@/services/auth'
import { useSessionStore } from '@/stores/session'

// Establishes the session (Keycloak when enabled, a dev bearer locally), then
// returns to the originally-requested route.
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

onMounted(async () => {
  if (!session.isAuthenticated) await initAuth()
  if (session.isAuthenticated) {
    const redirect = (route.query.redirect as string) || '/'
    router.replace(redirect)
  }
})
</script>

<template>
  <main class="login">
    <h1>Sysifos</h1>
    <p>Signing you in…</p>
  </main>
</template>
