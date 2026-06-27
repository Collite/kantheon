<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { storeToRefs } from 'pinia'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Select from 'primevue/select'
import DatePicker from 'primevue/datepicker'
import Tag from 'primevue/tag'
import SelectButton from 'primevue/selectbutton'
import { useToast } from 'primevue/usetoast'
import TransactionForm from '@/components/forms/TransactionForm.vue'
import BulkEntryGrid from '@/components/grids/BulkEntryGrid.vue'
import { usePortfolios } from '@/api/portfolios'
import { useTransactionsScreen, useCreateTransaction, useEditTransaction } from '@/api/transactions'
import { useDictionariesStore } from '@/stores/dictionaries'
import type { Transaction, TransactionRow } from '@/api/types'

const { t } = useI18n()
const toast = useToast()
const dictionaries = useDictionariesStore()
const { currencies, transactionKinds } = storeToRefs(dictionaries)

// Security-leg kinds only — derived TX_CASH_* legs are never user-entered (§5).
const entryKinds = computed(() => transactionKinds.value.filter((k) => !k.code.startsWith('TX_CASH_')))

onMounted(() => dictionaries.ensureLoaded())

// Portfolio picker — the screen is per-portfolio, so nothing loads until one is chosen.
const portfolioParams = computed(() => ({ page: 0, size: 200, status: 'PORTFOLIO_ACTIVE' }))
const { data: pfData } = usePortfolios(portfolioParams)
const portfolios = computed(() => pfData.value?.portfolios ?? [])
const portfolioId = ref<string | undefined>(undefined)

// Date range + quick ranges ("this month" / "YTD") seed the from/to filter.
const range = ref<Date[] | null>(null)
type QuickRange = 'month' | 'ytd'
const quick = ref<QuickRange | null>(null)
const quickOptions = computed(() => [
  { label: t('transactions.thisMonth'), value: 'month' as QuickRange },
  { label: t('transactions.ytd'), value: 'ytd' as QuickRange },
])
function applyQuick(q: QuickRange | null) {
  if (!q) return
  const now = new Date()
  const start = q === 'month' ? new Date(now.getFullYear(), now.getMonth(), 1) : new Date(now.getFullYear(), 0, 1)
  range.value = [start, now]
}
const fromTo = computed(() => {
  const [from, to] = range.value ?? []
  return { from: from?.toISOString().slice(0, 10), to: to?.toISOString().slice(0, 10) }
})

const screenParams = computed(() => ({
  portfolio_id: portfolioId.value,
  from: fromTo.value.from,
  to: fromTo.value.to,
  page: 0,
  size: 2000,
}))
const { data: screen, isLoading } = useTransactionsScreen(screenParams)
const assets = computed(() => screen.value?.assets ?? [])

// Flatten security legs + their derived cash legs into one virtual-scroll list,
// the cash legs immediately under their security row and dimmed via rowClass.
interface DisplayRow extends Transaction {
  _leg: 'security' | 'cash'
  _reversed?: boolean
}
const displayRows = computed<DisplayRow[]>(() => {
  const out: DisplayRow[] = []
  for (const tx of screen.value?.transactions ?? ([] as TransactionRow[])) {
    out.push({ ...tx, _leg: 'security', _reversed: !!tx.reversesTransactionId })
    for (const cash of tx.cashLegs ?? []) out.push({ ...cash, _leg: 'cash' })
  }
  return out
})
function rowClass(row: DisplayRow) {
  return { 'cash-leg': row._leg === 'cash', reversed: row._reversed }
}

const symbolOf = (assetId: string) => assets.value.find((a) => a.assetId === assetId)?.symbol ?? assetId

// Single manual entry + inline edit (reverse + replace).
const create = useCreateTransaction()
const edit = useEditTransaction()
const dialogOpen = ref(false)
const editing = ref<Transaction | undefined>(undefined)
const bulkOpen = ref(false)

function openCreate() {
  editing.value = undefined
  dialogOpen.value = true
}
function openEdit(row: DisplayRow) {
  editing.value = { ...row }
  dialogOpen.value = true
}

async function onSubmit(tx: Transaction) {
  try {
    if (tx.transactionId) {
      await edit.mutateAsync({ id: tx.transactionId, newTransaction: tx })
      toast.add({ severity: 'success', summary: t('transactions.edited'), life: 2500 })
    } else {
      const res = await create.mutateAsync(tx)
      const legs = res.transaction ? 1 : 0
      toast.add({ severity: 'success', summary: t('transactions.created', { legs }), life: 2500 })
    }
    dialogOpen.value = false
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  }
}
</script>

<template>
  <section>
    <header class="screen-head">
      <h2>{{ t('transactions.title') }}</h2>
      <div class="head-actions">
        <Button
          :label="t('bulk.title')"
          icon="pi pi-table"
          severity="secondary"
          :disabled="!portfolioId"
          data-test="bulk-entry"
          @click="bulkOpen = true"
        />
        <Button
          :label="t('transactions.add')"
          icon="pi pi-plus"
          :disabled="!portfolioId"
          data-test="add-tx"
          @click="openCreate"
        />
      </div>
    </header>

    <div class="toolbar">
      <Select
        v-model="portfolioId"
        :options="portfolios"
        option-label="name"
        option-value="portfolioId"
        :placeholder="t('transactions.choosePortfolio')"
        data-test="tx-portfolio"
      />
      <DatePicker v-model="range" selection-mode="range" date-format="yy-mm-dd" :placeholder="t('transactions.dateRange')" />
      <SelectButton v-model="quick" :options="quickOptions" option-label="label" option-value="value" @change="applyQuick(quick)" />
    </div>

    <p v-if="!portfolioId" class="hint" data-test="tx-empty">{{ t('transactions.choosePortfolio') }}</p>

    <DataTable
      v-else
      :value="displayRows"
      :loading="isLoading"
      data-key="transactionId"
      :row-class="rowClass"
      scrollable
      scroll-height="60vh"
      :virtual-scroller-options="{ itemSize: 44 }"
      data-test="tx-table"
    >
      <Column field="tradeDate" :header="t('transactions.tradeDate')" />
      <Column :header="t('transactions.asset')">
        <template #body="{ data: row }">
          <span :class="{ 'cash-symbol': row._leg === 'cash' }">{{ symbolOf(row.assetId) }}</span>
        </template>
      </Column>
      <Column field="kind" :header="t('transactions.kind')" />
      <Column field="quantity" :header="t('transactions.quantity')" />
      <Column :header="t('transactions.price')">
        <template #body="{ data: row }">{{ row.price?.amount ?? '—' }}</template>
      </Column>
      <Column :header="''">
        <template #body="{ data: row }">
          <Tag v-if="row._reversed" :value="t('transactions.reversed')" severity="secondary" />
          <Button
            v-if="row._leg === 'security' && !row._reversed"
            text
            icon="pi pi-pencil"
            :aria-label="t('common.edit')"
            data-test="tx-edit"
            @click="openEdit(row)"
          />
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="dialogOpen" modal :header="t('transactions.title')">
      <TransactionForm
        v-if="portfolioId"
        :portfolio-id="portfolioId"
        :initial="editing"
        :assets="assets"
        :currencies="currencies"
        :transaction-kinds="entryKinds"
        @submit="onSubmit"
        @cancel="dialogOpen = false"
      />
    </Dialog>

    <Dialog v-model:visible="bulkOpen" modal :header="t('bulk.title')" :style="{ width: '90vw' }">
      <BulkEntryGrid
        v-if="portfolioId"
        :portfolio-id="portfolioId"
        :assets="assets"
        :currencies="currencies"
        :transaction-kinds="entryKinds"
        @committed="bulkOpen = false"
      />
    </Dialog>
  </section>
</template>

<style scoped>
.screen-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.head-actions {
  display: flex;
  gap: 0.5rem;
}
.toolbar {
  display: flex;
  gap: 1rem;
  align-items: center;
  margin: 0.75rem 0;
  flex-wrap: wrap;
}
.hint {
  color: var(--p-text-muted-color, #6b7280);
}
:deep(.cash-leg) {
  opacity: 0.6;
  font-style: italic;
}
.cash-symbol::before {
  content: '↳ ';
}
:deep(.reversed) {
  opacity: 0.5;
  text-decoration: line-through;
}
</style>
