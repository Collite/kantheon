<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import { useQueryClient } from '@tanstack/vue-query'
import AssetQuickCreate from '@/components/forms/AssetQuickCreate.vue'
import { useImportScreen, commitLoaderRun } from '@/api/loaders'
import { useDraftsStore } from '@/stores/drafts'
import type { PreviewDecision, PreviewRow } from '@/api/types'

const { t } = useI18n()
const toast = useToast()
const route = useRoute()
const router = useRouter()
const drafts = useDraftsStore()
const queryClient = useQueryClient()

const loaderRunId = computed(() => route.params.loaderRunId as string)
const { data: screen, isLoading } = useImportScreen(loaderRunId)

interface Row extends PreviewRow {
  _decision: PreviewDecision
}
// Local working copy so an inline symbol correction moves its row to NEW immediately.
const rows = ref<Row[]>([])
watch(
  () => screen.value?.rows,
  (incoming) => {
    rows.value = (incoming ?? []).map((r) => ({ ...r, _decision: r.decision ?? 'PV_NEW' }))
  },
  { immediate: true },
)

const summary = computed(() => screen.value?.summary)
const groups: PreviewDecision[] = ['PV_NEW', 'PV_DUPLICATE', 'PV_ERROR']
const badgeSeverity: Record<PreviewDecision, string> = {
  PV_NEW: 'success',
  PV_DUPLICATE: 'warn',
  PV_ERROR: 'danger',
}
function rowsOf(decision: PreviewDecision) {
  return rows.value.filter((r) => r._decision === decision)
}
// The loader commits the whole run (NEW rows imported, duplicates/errors skipped);
// there is no per-row selection in the commit contract, so the count of NEW rows is
// what will actually be imported.
const newCount = computed(() => summary.value?.newCount ?? rowsOf('PV_NEW').length)

// Inline correction — resolve an unknown symbol on an ERROR row → flips it NEW.
const quickOpen = ref(false)
const quickSymbol = ref('')
const correctingRow = ref<Row | null>(null)
function correct(row: Row) {
  quickSymbol.value = row.draft?.assetId ?? ''
  correctingRow.value = row
  quickOpen.value = true
}
function onAssetCreated() {
  if (correctingRow.value) correctingRow.value._decision = 'PV_NEW'
  correctingRow.value = null
  // Re-diff against Midas now that the asset exists, so the correction is reflected
  // in what the run actually commits (the loader re-evaluates on commit).
  queryClient.invalidateQueries({ queryKey: ['import-screen', loaderRunId] })
}

const draftId = ref<string | null>(null)
const committing = ref(false)
async function commit() {
  committing.value = true
  try {
    const res = await commitLoaderRun(loaderRunId.value, true)
    draftId.value = res.draft_id
    toast.add({ severity: 'info', summary: t('import.committing'), life: 2500 })
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  } finally {
    committing.value = false
  }
}

watch(
  () => (draftId.value ? drafts.byId[draftId.value]?.status : undefined),
  (s) => {
    if (s === 'COMMITTED') {
      const st = drafts.byId[draftId.value!]
      toast.add({
        severity: 'success',
        summary: t('import.committed', { n: st?.committedCount ?? 0, s: st?.skippedCount ?? 0 }),
        life: 4000,
      })
      router.push({ name: 'transactions' })
    }
  },
)
</script>

<template>
  <section>
    <header class="screen-head">
      <h2>{{ t('import.previewTitle') }}</h2>
      <Button
        :label="t('import.commit', { n: newCount })"
        :disabled="newCount === 0 || committing"
        data-test="import-commit"
        @click="commit"
      />
    </header>

    <Message v-if="summary" severity="info" data-test="import-summary">
      {{ t('import.summary', { n: summary.newCount ?? 0, d: summary.duplicateCount ?? 0, e: summary.errorCount ?? 0 }) }}
    </Message>

    <div v-for="decision in groups" :key="decision" class="decision-group">
      <h3>
        <Tag :value="t(`import.decision.${decision}`)" :severity="badgeSeverity[decision]" />
        <span class="count">{{ rowsOf(decision).length }}</span>
      </h3>
      <DataTable
        v-if="rowsOf(decision).length"
        :value="rowsOf(decision)"
        :loading="isLoading"
        data-key="sourceRowIndex"
        :data-test="`import-rows-${decision}`"
      >
        <Column field="sourceRowIndex" :header="t('import.sourceRow')" />
        <Column :header="t('transactions.kind')">
          <template #body="{ data: row }">{{ row.draft?.kind ?? '—' }}</template>
        </Column>
        <Column :header="t('transactions.quantity')">
          <template #body="{ data: row }">{{ row.draft?.quantity ?? '—' }}</template>
        </Column>
        <Column field="note" :header="t('import.note')" />
        <Column v-if="decision === 'PV_ERROR'" :header="''">
          <template #body="{ data: row }">
            <Button
              text
              size="small"
              :label="t('import.fix')"
              :data-test="`fix-${row.sourceRowIndex}`"
              @click="correct(row)"
            />
          </template>
        </Column>
      </DataTable>
    </div>

    <AssetQuickCreate v-model:visible="quickOpen" :symbol="quickSymbol" @created="onAssetCreated" />
  </section>
</template>

<style scoped>
.screen-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.decision-group {
  margin: 1rem 0;
}
.decision-group h3 {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.count {
  color: var(--p-text-muted-color, #6b7280);
}
</style>
