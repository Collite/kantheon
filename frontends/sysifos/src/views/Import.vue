<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import Select from 'primevue/select'
import Button from 'primevue/button'
import ProgressBar from 'primevue/progressbar'
import Message from 'primevue/message'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import { useToast } from 'primevue/usetoast'
import { uploadStatement, useLoaderRuns, useLoaderRunStatus } from '@/api/loaders'
import { useDictionariesStore } from '@/stores/dictionaries'
import { useLoadersStore } from '@/stores/loaders'
import { usePortfolios } from '@/api/portfolios'

const { t } = useI18n()
const toast = useToast()
const router = useRouter()
const dictionaries = useDictionariesStore()
const { brokers } = storeToRefs(dictionaries)
const loaders = useLoadersStore()

onMounted(() => dictionaries.ensureLoaded())

const brokerId = ref<string | undefined>(undefined)
const portfolioId = ref<string | undefined>(undefined)
const file = ref<File | null>(null)
const uploading = ref(false)
const runId = ref<string | null>(null)

const { data: pfData } = usePortfolios(computed(() => ({ page: 0, size: 200, status: 'PORTFOLIO_ACTIVE' })))
const portfolios = computed(() => pfData.value?.portfolios ?? [])

const canUpload = computed(() => !!brokerId.value && !!portfolioId.value && !!file.value && !uploading.value)

// The loader doesn't push progress onto the SSE bus in v1 — poll the run status and
// feed it into the loaders store, which the progress bar + preview watch read.
const { data: runStatus } = useLoaderRunStatus(runId)
watch(runStatus, (run) => run && loaders.applyRun(run))

const progress = computed(() => (runId.value ? loaders.byRunId[runId.value] : undefined))
const progressPct = computed(() => {
  const p = progress.value
  if (!p?.rowsTotal) return 0
  return Math.round(((p.rowsProcessed ?? 0) / p.rowsTotal) * 100)
})

function onFile(e: Event) {
  file.value = (e.target as HTMLInputElement).files?.[0] ?? null
}

async function upload() {
  if (!file.value || !brokerId.value || !portfolioId.value) return
  uploading.value = true
  try {
    const res = await uploadStatement(file.value, brokerId.value, portfolioId.value)
    runId.value = res.loaderRunId ?? null
  } catch {
    toast.add({ severity: 'error', summary: t('import.uploadFailed'), life: 3000 })
  } finally {
    uploading.value = false
  }
}

// When the loader signals preview-ready, jump to the preview screen.
watch(
  () => (runId.value ? progress.value?.previewReady : false),
  (ready) => {
    if (ready && runId.value) router.push({ name: 'import-preview', params: { loaderRunId: runId.value } })
  },
)

// History — past runs.
const runsParams = computed(() => ({ page: 0, size: 50, portfolio_id: portfolioId.value }))
const { data: runsData } = useLoaderRuns(runsParams)
const runs = computed(() => runsData.value?.runs ?? [])
function openRun(id?: string) {
  if (id) router.push({ name: 'import-preview', params: { loaderRunId: id } })
}
</script>

<template>
  <section>
    <h2>{{ t('import.title') }}</h2>

    <div class="upload-row">
      <Select
        v-model="brokerId"
        :options="brokers"
        option-label="displayName"
        option-value="brokerId"
        :placeholder="t('import.broker')"
        data-test="import-broker"
      />
      <Select
        v-model="portfolioId"
        :options="portfolios"
        option-label="name"
        option-value="portfolioId"
        :placeholder="t('import.portfolio')"
        data-test="import-portfolio"
      />
      <input type="file" accept=".xlsx,.xls" data-test="import-file" @change="onFile" />
      <Button :label="t('import.upload')" :disabled="!canUpload" data-test="import-upload" @click="upload" />
    </div>

    <div v-if="runId" class="progress" data-test="import-progress">
      <span>{{ t(`import.phase.${progress?.phase ?? 'LP_PARSING'}`) }}</span>
      <ProgressBar :value="progressPct" />
    </div>

    <Message v-if="progress?.previewReady" severity="success" data-test="import-ready">
      {{ t('import.previewReady', { n: progress?.newCount ?? 0, d: progress?.duplicateCount ?? 0 }) }}
    </Message>

    <h3>{{ t('import.history') }}</h3>
    <DataTable :value="runs" data-key="loaderRunId" data-test="import-history">
      <Column field="uploadedAt" :header="t('import.uploadedAt')" />
      <Column field="brokerId" :header="t('import.broker')" />
      <Column field="status" :header="t('import.status')" />
      <Column field="rowCountTotal" :header="t('import.rows')" />
      <Column :header="''">
        <template #body="{ data: row }">
          <Button text icon="pi pi-eye" :aria-label="t('common.edit')" @click="openRun(row.loaderRunId)" />
        </template>
      </Column>
    </DataTable>
  </section>
</template>

<style scoped>
.upload-row {
  display: flex;
  gap: 1rem;
  align-items: center;
  margin: 0.75rem 0;
  flex-wrap: wrap;
}
.progress {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 1rem 0;
}
</style>
