<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useConnectionStatus } from '@/composables/useConnectionStatus'

const props = defineProps<{
    baseUrl: string
    userId: string | null
}>()

const connectionStatus = useConnectionStatus()
const { status, lastSuccessAt } = connectionStatus
const { t } = useI18n()

onMounted(() => {
    connectionStatus.start(props.baseUrl, props.userId)
})

onUnmounted(() => {
    connectionStatus.stop()
})

const statusColor = computed(() => {
    switch (status.value) {
        case 'ok':
            return 'var(--p-green-500)'
        case 'degraded':
            return 'var(--p-orange-500)'
        case 'disconnected':
            return 'var(--p-red-500)'
    }
    return 'var(--p-surface-400)'
})

const ariaLabel = computed(() => {
    switch (status.value) {
        case 'ok':
            return t('chat.connection.status.ok')
        case 'degraded':
            return t('chat.connection.status.degraded')
        case 'disconnected':
            return t('chat.connection.status.disconnected')
    }
    return ''
})

const formattedTime = computed(() => {
    if (!lastSuccessAt.value) return null
    return lastSuccessAt.value.toLocaleTimeString()
})
</script>

<template>
    <div class="connection-status-dot-wrapper">
        <div
            class="connection-status-dot"
            :style="{ backgroundColor: statusColor }"
            :aria-label="ariaLabel"
            :title="lastSuccessAt ? t('chat.connection.lastPoll', { time: formattedTime }) + ` — ${ariaLabel}` : ariaLabel"
        />
    </div>
</template>

<style scoped>
.connection-status-dot-wrapper {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 1.5rem;
    height: 1.5rem;
}

.connection-status-dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    cursor: default;
    flex-shrink: 0;
}
</style>
