<script setup lang="ts">
import Card from 'primevue/card'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <div class="empty-host h-full w-full flex items-center justify-center p-6">
    <Card class="empty-card max-w-md text-center">
      <template #content>
        <div class="flex flex-col items-center gap-3">
          <div class="icon-circle">
            <i class="pi pi-window-maximize" aria-hidden="true"></i>
          </div>
          <h2 class="text-lg font-semibold m-0" style="color: var(--p-surface-900);">
            {{ t('tabs.emptyTitle') }}
          </h2>
          <p class="text-sm leading-relaxed m-0" style="color: var(--p-surface-600);">
            {{ t('tabs.emptyBody') }}
          </p>
        </div>
      </template>
    </Card>
  </div>
</template>

<style scoped>
.empty-host {
  background-color: var(--p-surface-50);
}

.icon-circle {
  width: 3rem;
  height: 3rem;
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--p-primary-100);
  color: var(--p-primary-700);
  font-size: 1.25rem;
}
</style>
