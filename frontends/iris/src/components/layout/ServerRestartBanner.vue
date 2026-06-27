<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'

const { t } = useI18n()
const visible = ref(false)

let dismissedUntil: number | null = null

const onServerRestart = () => {
  if (dismissedUntil && Date.now() < dismissedUntil) return
  visible.value = true

  setTimeout(() => {
    visible.value = false
    dismissedUntil = Date.now() + 10_000
  }, 10_000)
}

const dismiss = () => {
  visible.value = false
  dismissedUntil = Date.now() + 10_000
}

onMounted(() => {
  window.addEventListener('server-restart', onServerRestart)
})

onUnmounted(() => {
  window.removeEventListener('server-restart', onServerRestart)
})
</script>

<template>
  <Message
    v-if="visible"
    severity="info"
    :closable="true"
    @close="dismiss"
  >
    <span class="font-medium">
      {{ t('chat.connection.restartBannerTitle') }}
    </span>
    <span class="text-sm ml-1 opacity-80">
      {{ t('chat.connection.restartBannerBody') }}
    </span>
  </Message>
</template>

<style scoped>
</style>
