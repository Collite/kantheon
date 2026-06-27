<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import { useLoaderRuns } from '@/api/loaders'
import type { LoaderRun, LoaderRunStatus } from '@/api/types'

const { t } = useI18n()
const router = useRouter()

const params = computed(() => ({ page: 0, size: 50 }))
const { data, isLoading } = useLoaderRuns(params)
const runs = computed(() => data.value?.runs ?? [])

const statusSeverity: Record<LoaderRunStatus, string> = {
  LR_UPLOADED: 'secondary',
  LR_PARSING: 'info',
  LR_MAPPING: 'info',
  LR_PREVIEW_READY: 'warn',
  LR_COMMITTING: 'info',
  LR_COMPLETED: 'success',
  LR_FAILED: 'danger',
}

const detail = ref<LoaderRun | null>(null)
const detailOpen = ref(false)
function openDetail(run: LoaderRun) {
  detail.value = run
  detailOpen.value = true
}
function viewPreview(run: LoaderRun) {
  if (run.loaderRunId) router.push({ name: 'import-preview', params: { loaderRunId: run.loaderRunId } })
}
</script>

<template>
  <section>
    <h2>{{ t('loaders.title') }}</h2>

    <DataTable :value="runs" :loading="isLoading" data-key="loaderRunId" paginator :rows="50" data-test="loaders-table">
      <Column field="sourceKind" :header="t('loaders.source')" />
      <Column field="brokerId" :header="t('loaders.broker')" />
      <Column field="uploadedAt" :header="t('loaders.uploaded')" />
      <Column :header="t('loaders.status')">
        <template #body="{ data: row }">
          <Tag :value="row.status" :severity="statusSeverity[row.status as LoaderRunStatus] ?? 'secondary'" />
        </template>
      </Column>
      <Column field="rowCountTotal" :header="t('loaders.rows')" />
      <Column :header="''">
        <template #body="{ data: row }">
          <Button text icon="pi pi-info-circle" :aria-label="t('loaders.details')" @click="openDetail(row)" />
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="detailOpen" modal :header="t('loaders.details')" data-test="loader-detail">
      <div v-if="detail" class="detail">
        <p>{{ t('loaders.rows') }}: {{ detail.rowCountTotal ?? 0 }}</p>
        <p>
          {{ t('import.committed', { n: detail.rowCountCommitted ?? 0, s: detail.rowCountSkipped ?? 0 }) }},
          {{ detail.rowCountFailed ?? 0 }} {{ t('import.decision.PV_ERROR') }}
        </p>
        <Button :label="t('loaders.viewPreview')" data-test="loader-view-preview" @click="viewPreview(detail)" />
      </div>
    </Dialog>
  </section>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  min-width: 22rem;
}
</style>
