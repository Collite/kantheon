<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { useRoute, RouterView } from 'vue-router'
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import AppLayout from '@/components/layout/AppLayout.vue'
import { useSessionStore } from '@/stores/session'
import { useSysifosStream } from '@/composables/useSysifosStream'

const route = useRoute()
const session = useSessionStore()
const { connect } = useSysifosStream()

// Open the SSE stream once authenticated; tear it down on logout/unmount.
let abort: AbortController | null = null
function openStream() {
  abort?.abort()
  abort = new AbortController()
  connect(abort.signal).catch(() => {})
}

watch(
  () => session.isAuthenticated,
  (authed) => {
    if (authed) openStream()
  },
)

onMounted(() => {
  if (session.isAuthenticated) openStream()
})
onUnmounted(() => abort?.abort())
</script>

<template>
  <Toast position="top-right" />
  <ConfirmDialog />
  <AppLayout v-if="session.isAuthenticated && !route.meta.public">
    <RouterView />
  </AppLayout>
  <RouterView v-else />
</template>
