<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import AssetQuickCreate from '@/components/forms/AssetQuickCreate.vue'
import { useDraftsStore } from '@/stores/drafts'
import { submitDraft } from '@/api/drafts'
import {
  GRID_FIELDS,
  type GridField,
  type GridRow,
  emptyRow,
  parsePastedBlock,
  rowValid,
  rowToForm,
  validateCell,
} from '@/validation/transaction'
import type { Asset } from '@/api/types'
import type { LabelledEntry } from '@/stores/dictionaries'

const props = defineProps<{
  portfolioId: string
  assets: Asset[]
  currencies: LabelledEntry[]
  transactionKinds: LabelledEntry[]
}>()
const emit = defineEmits<{ committed: [] }>()
const { t } = useI18n()
const toast = useToast()
const drafts = useDraftsStore()

const localAssets = ref<Asset[]>([...props.assets])
watch(
  () => props.assets,
  (a) => {
    if (localAssets.value.length === 0) localAssets.value = [...a]
  },
)
const symbolIndex = computed(() => {
  const m = new Map<string, Asset>()
  for (const a of localAssets.value) m.set(a.symbol.toUpperCase(), a)
  return m
})
function resolve(symbol: string): Asset | undefined {
  return symbolIndex.value.get(symbol.trim().toUpperCase())
}

const rows = ref<GridRow[]>([emptyRow(), emptyRow(), emptyRow()])

function onPaste(e: ClipboardEvent) {
  const text = e.clipboardData?.getData('text/plain')
  if (!text || !text.includes('\t')) return // single-cell paste → let the input handle it
  e.preventDefault()
  const pasted = parsePastedBlock(text)
  if (pasted.length) rows.value = pasted
}

function addRow() {
  rows.value.push(emptyRow())
}
function removeRow(i: number) {
  rows.value.splice(i, 1)
}

const cellError = (field: GridField, row: GridRow) => validateCell(field, row[field])
const unknownSymbol = (row: GridRow) => !!row.symbol.trim() && !resolve(row.symbol)

const invalidCount = computed(() => rows.value.filter((r) => !rowValid(r) || unknownSymbol(r)).length)
const nonEmptyRows = computed(() => rows.value.filter((r) => r.symbol.trim() || r.quantity.trim()))
const canCommit = computed(() => nonEmptyRows.value.length > 0 && invalidCount.value === 0)

// Quick-create: queue every unknown symbol; resolve them one modal pass.
const quickOpen = ref(false)
const quickQueue = ref<string[]>([])
const quickSymbol = computed(() => quickQueue.value[0] ?? '')
function openQuickCreate() {
  const unknowns = [...new Set(rows.value.filter(unknownSymbol).map((r) => r.symbol.trim()))]
  if (!unknowns.length) return
  quickQueue.value = unknowns
  quickOpen.value = true
}
function onAssetCreated(asset: Asset) {
  localAssets.value = [asset, ...localAssets.value]
  // Backfill the resolved asset id into every row carrying that symbol.
  for (const r of rows.value) if (r.symbol.trim().toUpperCase() === asset.symbol.toUpperCase()) r.assetId = asset.assetId
  quickQueue.value = quickQueue.value.slice(1)
  if (quickQueue.value.length) quickOpen.value = true
}

const draftId = ref<string | null>(null)
const submitting = ref(false)
const draftState = computed(() => (draftId.value ? drafts.byId[draftId.value] : undefined))

function rowStatus(i: number) {
  return draftState.value?.rows?.[i]
}

async function commit() {
  for (const r of rows.value) {
    const a = resolve(r.symbol)
    if (a) r.assetId = a.assetId
  }
  const payloadRows = nonEmptyRows.value.map((r) => rowToForm(r, props.portfolioId))
  const payload = { portfolioId: props.portfolioId, rows: payloadRows, skipExisting: true }
  submitting.value = true
  try {
    const res = await submitDraft('DRAFT_TRANSACTION_BATCH', payload)
    draftId.value = res.draft_id
    toast.add({ severity: 'info', summary: t('bulk.submitted', { n: payloadRows.length }), life: 2500 })
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  } finally {
    submitting.value = false
  }
}

watch(
  () => draftState.value?.status,
  (s) => {
    if (s === 'COMMITTED') emit('committed')
  },
)
</script>

<template>
  <div class="bulk-grid" data-test="bulk-grid" @paste="onPaste">
    <p class="hint">{{ t('bulk.pasteHint') }}</p>

    <DataTable :value="rows" edit-mode="cell" data-key="__idx" scrollable scroll-height="50vh" data-test="bulk-table">
      <Column v-for="field in GRID_FIELDS" :key="field" :field="field" :header="t(`transactions.${field}`)">
        <template #body="{ data: row, index }">
          <span :class="{ 'cell-err': cellError(field, row), 'cell-warn': field === 'symbol' && unknownSymbol(row) }">
            {{ row[field] || '—' }}
          </span>
          <span v-if="field === 'symbol' && index === 0 && unknownSymbol(row)" />
        </template>
        <template #editor="{ data: row }">
          <Select
            v-if="field === 'kind'"
            v-model="row.kind"
            :options="transactionKinds"
            option-label="cs"
            option-value="code"
          />
          <Select
            v-else-if="field === 'currency'"
            v-model="row.currency"
            :options="currencies"
            option-label="code"
            option-value="code"
          />
          <InputText v-else v-model="row[field]" :data-test="`cell-${field}`" autofocus />
        </template>
      </Column>
      <Column :header="t('bulk.status')">
        <template #body="{ index }">
          <Tag v-if="rowStatus(index)?.outcome === 'BR_COMMITTED'" :value="t('bulk.committed')" severity="success" />
          <Tag v-else-if="rowStatus(index)?.outcome === 'BR_SKIPPED'" :value="t('bulk.skipped')" severity="warn" />
          <Tag
            v-else-if="rowStatus(index)?.outcome === 'BR_FAILED'"
            :value="rowStatus(index)?.message || t('bulk.failed')"
            severity="danger"
          />
          <Button text icon="pi pi-trash" :aria-label="t('common.cancel')" @click="removeRow(index)" />
        </template>
      </Column>
    </DataTable>

    <footer class="bulk-foot">
      <span data-test="row-count">{{ t('bulk.rowCount', { n: nonEmptyRows.length, bad: invalidCount }) }}</span>
      <Button text icon="pi pi-plus" :label="t('bulk.addRow')" data-test="bulk-add-row" @click="addRow" />
      <Button
        v-if="rows.some(unknownSymbol)"
        severity="warn"
        icon="pi pi-question-circle"
        :label="t('bulk.resolveUnknown')"
        data-test="bulk-resolve"
        @click="openQuickCreate"
      />
      <Button
        :label="t('bulk.commit', { n: nonEmptyRows.length })"
        :disabled="!canCommit || submitting"
        data-test="bulk-commit"
        @click="commit"
      />
    </footer>

    <AssetQuickCreate v-model:visible="quickOpen" :symbol="quickSymbol" @created="onAssetCreated" />
  </div>
</template>

<style scoped>
.bulk-grid {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  min-width: 56rem;
}
.hint {
  color: var(--p-text-muted-color, #6b7280);
  margin: 0;
}
.bulk-foot {
  display: flex;
  gap: 1rem;
  align-items: center;
  justify-content: flex-end;
}
.cell-err {
  color: var(--p-red-500, #ef4444);
  font-weight: 600;
}
.cell-warn {
  color: var(--p-orange-500, #f59e0b);
}
</style>
