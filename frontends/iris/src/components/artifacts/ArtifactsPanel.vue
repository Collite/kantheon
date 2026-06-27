<script setup lang="ts">
// Iris Phase 4 Stage 4.2 — pins & dashboards panel (PD-6). Lists the caller's
// pins as refreshable tiles and dashboards as openable collections; opening a
// dashboard streams a parallel per-pin refresh (SSE) into the tile grid.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import PinTile from './PinTile.vue'
import { artifactsApi } from '@/services/artifacts'
import type { ArtifactDto } from '@/types/artifacts'

const { t } = useI18n()
const toast = useToast()

const pins = ref<ArtifactDto[]>([])
const dashboards = ref<ArtifactDto[]>([])
const openDashboard = ref<ArtifactDto | null>(null)
const dashboardTiles = ref<ArtifactDto[]>([])
const loading = ref(false)
// Per-pin refresh state: a refresh is async here, so the tile spinner has to be
// driven by the parent (the tile can't know when its own emit completes).
const refreshingIds = ref<Set<string>>(new Set())

const load = async () => {
  loading.value = true
  try {
    const all = await artifactsApi.list()
    pins.value = all.filter((a) => a.kind === 'pin')
    dashboards.value = all.filter((a) => a.kind === 'dashboard')
  } catch (err) {
    console.error('[ArtifactsPanel] load failed', err)
    toast.add({ severity: 'error', summary: t('artifacts.loadFailed'), life: 3000 })
  } finally {
    loading.value = false
  }
}

const onRefresh = async (id: string) => {
  refreshingIds.value = new Set(refreshingIds.value).add(id)
  try {
    const updated = await artifactsApi.refresh(id)
    replacePin(updated)
  } catch (err) {
    console.error('[ArtifactsPanel] refresh failed', err)
    toast.add({ severity: 'error', summary: t('artifacts.refreshFailed'), life: 3000 })
  } finally {
    const next = new Set(refreshingIds.value)
    next.delete(id)
    refreshingIds.value = next
  }
}

const onRemove = async (id: string) => {
  // Prune only on success; a failed delete must leave the tile in place (and surface).
  try {
    await artifactsApi.remove(id)
  } catch (err) {
    console.error('[ArtifactsPanel] remove failed', err)
    toast.add({ severity: 'error', summary: t('artifacts.removeFailed'), life: 3000 })
    return
  }
  pins.value = pins.value.filter((p) => p.artifactId !== id)
  dashboardTiles.value = dashboardTiles.value.filter((p) => p.artifactId !== id)
}

const replacePin = (updated: ArtifactDto) => {
  const inList = pins.value.findIndex((p) => p.artifactId === updated.artifactId)
  if (inList >= 0) pins.value[inList] = updated
  const inTiles = dashboardTiles.value.findIndex((p) => p.artifactId === updated.artifactId)
  if (inTiles >= 0) dashboardTiles.value[inTiles] = updated
}

const onOpenDashboard = async (dash: ArtifactDto) => {
  openDashboard.value = dash
  dashboardTiles.value = []
  try {
    await artifactsApi.openDashboard(dash.artifactId, (pin) => {
      dashboardTiles.value.push(pin)
    })
  } catch (err) {
    console.error('[ArtifactsPanel] open dashboard failed', err)
    toast.add({ severity: 'error', summary: t('artifacts.loadFailed'), life: 3000 })
  }
}

const backToList = () => {
  openDashboard.value = null
  dashboardTiles.value = []
  void load()
}

onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="artifacts-panel">
    <!-- Dashboard open: per-pin refreshed tiles -->
    <template v-if="openDashboard">
      <header class="artifacts-head">
        <Button text size="small" icon="pi pi-arrow-left" :label="t('artifacts.back')" @click="backToList" />
        <span class="artifacts-title">{{ openDashboard.name }}</span>
      </header>
      <div class="tile-grid">
        <PinTile
          v-for="tile in dashboardTiles"
          :key="tile.artifactId"
          :pin="tile"
          :loading="refreshingIds.has(tile.artifactId)"
          @refresh="onRefresh"
          @remove="onRemove"
        />
      </div>
    </template>

    <!-- List view: dashboards + pins -->
    <template v-else>
      <h3 class="artifacts-title">{{ t('artifacts.title') }}</h3>

      <section v-if="dashboards.length > 0" class="artifacts-section">
        <h4>{{ t('artifacts.dashboards') }}</h4>
        <ul class="dashboard-list">
          <li v-for="d in dashboards" :key="d.artifactId">
            <Button
              text
              size="small"
              icon="pi pi-th-large"
              :label="d.name"
              @click="onOpenDashboard(d)"
            />
          </li>
        </ul>
      </section>

      <section class="artifacts-section">
        <h4>{{ t('artifacts.pins') }}</h4>
        <p v-if="!loading && pins.length === 0" class="artifacts-empty">{{ t('artifacts.empty') }}</p>
        <div class="tile-grid">
          <PinTile
            v-for="pin in pins"
            :key="pin.artifactId"
            :pin="pin"
            :loading="refreshingIds.has(pin.artifactId)"
            @refresh="onRefresh"
            @remove="onRemove"
          />
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.artifacts-panel {
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  overflow: auto;
  height: 100%;
}
.artifacts-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.artifacts-title {
  font-size: 0.95rem;
  font-weight: 600;
  margin: 0;
}
.artifacts-section h4 {
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  color: var(--p-surface-500);
  margin: 0 0 0.4rem 0;
}
.dashboard-list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.artifacts-empty {
  font-size: 0.8rem;
  color: var(--p-surface-500);
  font-style: italic;
}
.tile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(18rem, 1fr));
  gap: 0.6rem;
}
</style>
