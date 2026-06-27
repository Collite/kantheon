<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { storeToRefs } from 'pinia'
import Select from 'primevue/select'
import InputText from 'primevue/inputtext'
import DatePicker from 'primevue/datepicker'
import Button from 'primevue/button'
import Message from 'primevue/message'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import { useToast } from 'primevue/usetoast'
import { usePortfolios } from '@/api/portfolios'
import { useAssets } from '@/api/assets'
import { usePreviewBalanceEntry, useCommitBalanceEntry, useBalanceHistory } from '@/api/balanceEntries'
import { explainBalanceEntry } from '@/validation/balanceEntry'
import { BalanceEntryFormSchema } from '@/validation/generated'
import { useDictionariesStore } from '@/stores/dictionaries'
import type { BalanceEntryPreview } from '@/api/types'

const { t } = useI18n()
const toast = useToast()
const dictionaries = useDictionariesStore()
const { assetKinds } = storeToRefs(dictionaries)
onMounted(() => dictionaries.ensureLoaded())

const portfolioId = ref<string | undefined>(undefined)
const assetId = ref<string | undefined>(undefined)
const targetQuantity = ref('')
const asOf = ref<Date | null>(new Date('2026-06-24'))

const { data: pfData } = usePortfolios(computed(() => ({ page: 0, size: 200, status: 'PORTFOLIO_ACTIVE' })))
const portfolios = computed(() => pfData.value?.portfolios ?? [])
const { data: assetData } = useAssets(computed(() => ({ page: 0, size: 1000 })))
const assets = computed(() => assetData.value?.assets ?? [])
const symbolOf = (id?: string) => assets.value.find((a) => a.assetId === id)?.symbol ?? ''

const preview = usePreviewBalanceEntry()
const commit = useCommitBalanceEntry()
const previewData = ref<BalanceEntryPreview | null>(null)
const errors = ref<Record<string, string>>({})

const explanation = computed(() => (previewData.value ? explainBalanceEntry(previewData.value) : null))

function asOfIso(): string {
  return asOf.value ? asOf.value.toISOString().slice(0, 10) : ''
}

function buildInput() {
  return {
    portfolio_id: portfolioId.value ?? '',
    asset_id: assetId.value ?? '',
    target_quantity: targetQuantity.value,
    as_of: asOfIso(),
  }
}

function validate(): boolean {
  const parsed = BalanceEntryFormSchema.safeParse(buildInput())
  errors.value = parsed.success
    ? {}
    : Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message]))
  return parsed.success
}

async function runPreview() {
  previewData.value = null
  if (!validate()) return
  try {
    previewData.value = await preview.mutateAsync(buildInput())
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  }
}

async function runCommit() {
  try {
    await commit.mutateAsync(buildInput())
    toast.add({ severity: 'success', summary: t('balance.committed'), life: 2500 })
    previewData.value = null
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  }
}

// History tab — prior ADJUSTMENTs for the selected portfolio/asset.
const historyParams = computed(() => ({ portfolio_id: portfolioId.value, asset_id: assetId.value }))
const { data: history } = useBalanceHistory(historyParams)
const historyRows = computed(() => history.value?.transactions ?? [])
</script>

<template>
  <section>
    <h2>{{ t('balance.title') }}</h2>

    <div class="entry-grid">
      <label>
        {{ t('balance.portfolio') }}
        <Select
          v-model="portfolioId"
          :options="portfolios"
          option-label="name"
          option-value="portfolioId"
          filter
          data-test="be-portfolio"
        />
        <small v-if="errors.portfolio_id" class="err" data-test="err-portfolio">{{ t('common.required') }}</small>
      </label>
      <label>
        {{ t('balance.asset') }}
        <Select
          v-model="assetId"
          :options="assets"
          option-label="symbol"
          option-value="assetId"
          filter
          data-test="be-asset"
        />
        <small v-if="errors.asset_id" class="err" data-test="err-asset">{{ t('common.required') }}</small>
      </label>
      <label>
        {{ t('balance.target') }}
        <InputText v-model="targetQuantity" data-test="be-target" />
        <small v-if="errors.target_quantity" class="err" data-test="err-target">{{ t('balance.invalidTarget') }}</small>
      </label>
      <label>
        {{ t('balance.asOf') }}
        <DatePicker v-model="asOf" date-format="yy-mm-dd" :max-date="new Date('2026-06-24')" />
      </label>
      <Button :label="t('balance.preview')" data-test="be-preview" @click="runPreview" />
    </div>

    <div v-if="previewData" class="preview-panel" data-test="be-preview-panel">
      <Message v-if="!explanation?.hasDiff" severity="info" data-test="be-nodiff">
        {{ t('balance.noDiff') }}
      </Message>
      <template v-else>
        <p class="explain" data-test="be-explain">
          {{ t('balance.adjustment') }}: {{ explanation?.text(symbolOf(assetId)) }}
        </p>
        <Button severity="success" :label="t('balance.commit')" data-test="be-commit" @click="runCommit" />
      </template>
    </div>

    <h3 v-if="portfolioId">{{ t('balance.history') }}</h3>
    <DataTable v-if="portfolioId" :value="historyRows" data-key="transactionId" data-test="be-history">
      <Column field="tradeDate" :header="t('balance.asOf')" />
      <Column :header="t('balance.asset')">
        <template #body="{ data: row }">{{ symbolOf(row.assetId) }}</template>
      </Column>
      <Column field="quantity" :header="t('balance.diff')" />
      <Column field="note" :header="t('balance.reason')" />
    </DataTable>

    <!-- assetKinds preloaded for symbol display consistency across screens -->
    <span v-show="false">{{ assetKinds.length }}</span>
  </section>
</template>

<style scoped>
.entry-grid {
  display: flex;
  gap: 1rem;
  align-items: flex-end;
  flex-wrap: wrap;
  margin: 0.75rem 0;
}
.entry-grid label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.preview-panel {
  margin: 1rem 0;
  padding: 1rem;
  border: 1px solid var(--p-content-border-color, #e5e7eb);
  border-radius: 8px;
}
.explain {
  font-size: 1.1rem;
  font-weight: 600;
}
.err {
  color: var(--p-red-500, #ef4444);
}
</style>
