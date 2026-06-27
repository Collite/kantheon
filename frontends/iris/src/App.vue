<script setup lang="ts">
import { computed, watch } from 'vue'
import { RouterView } from 'vue-router'
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import { usePrimeVue } from 'primevue/config'
import { useI18n } from 'vue-i18n'
import AppLayout from './components/layout/AppLayout.vue'
import UserIdDialog from './components/UserIdDialog.vue'
import { useAuthStore } from './stores/auth'
import { useTheme } from './composables/useTheme'
import { config } from './config'
import { primevueLocaleFor } from './i18n'

const authStore = useAuthStore()
const primevue = usePrimeVue()
const { locale } = useI18n()

useTheme()

// Phase 8 (FE-8.1): keep PrimeVue's built-in locale messages (DataTable
// "no records to show", paginator, etc.) in sync with vue-i18n.
watch(
    locale,
    (lang) => {
        const overrides = primevueLocaleFor(lang)
        // PrimeVue's `PrimeVueLocaleOptions` requires every key — we only
        // want to override a subset, so cast through `any` for the merge.
        // Untouched keys keep their PrimeVue defaults.
        primevue.config.locale = {
            ...primevue.config.locale,
            ...overrides,
        } as any
    },
    { immediate: true },
)

const showFallbackAuth = computed(() => {
    if (!config.keycloak.authEnabled && !authStore.isAuthenticated) {
        return true
    }
    return false
})
</script>

<template>
  <Toast position="top-right" />
  <ConfirmDialog />
  <UserIdDialog v-if="showFallbackAuth" />
  <AppLayout v-else>
    <RouterView v-slot="{ Component }">
      <transition enter-active-class="transition ease-out duration-200" enter-from-class="opacity-0 translate-y-2" enter-to-class="opacity-100 translate-y-0" leave-active-class="transition ease-in duration-150" leave-from-class="opacity-100 translate-y-0" leave-to-class="opacity-0 translate-y-2" mode="out-in">
        <component :is="Component" />
      </transition>
    </RouterView>
  </AppLayout>
</template>
