<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Select from 'primevue/select'
import DatePicker from 'primevue/datepicker'
import Button from 'primevue/button'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import ToggleSwitch from 'primevue/toggleswitch'
import { useToast } from 'primevue/usetoast'
import { usePortfolios } from '@/api/portfolios'
import { useReconcile, useDecideDiff } from '@/api/reconcile'
import { DIFF_KINDS, diffKeyOf, groupDiffs } from '@/validation/reconcile'
import type { ReconcileDiff, ReconcileResponse, ReconcileStatus } from '@/api/types'

const { t } = useI18n()
const toast = useToast()

const portfolioId = ref<string | undefined>(undefined)
const period = ref<Date[] | null>(null)
const openOnly = ref(false)

const { data: pfData } = usePortfolios(computed(() => ({ page: 0, size: 200, status: 'PORTFOLIO_ACTIVE' })))
const portfolios = computed(() => pfData.value?.portfolios ?? [])

const reconcile = useReconcile()
const decide = useDecideDiff()
const result = ref<ReconcileResponse | null>(null)

const decisionOptions: ReconcileStatus[] = ['RECON_EXPECTED', 'RECON_INVESTIGATE', 'RECON_RESOLVED']
const kindSeverity: Record<string, string> = {
  RECON_SYSTEM_ONLY: 'warn',
  RECON_STATEMENT_ONLY: 'info',
  RECON_VALUE_MISMATCH: 'danger',
}

const groups = computed(() => groupDiffs(result.value?.diffs ?? [], openOnly.value))
const indexOf = (diff: ReconcileDiff) => (result.value?.diffs ?? []).indexOf(diff)

async function run() {
  if (!portfolioId.value) return
  const [start, end] = period.value ?? []
  try {
    result.value = await reconcile.mutateAsync({
      portfolio_id: portfolioId.value,
      period_start: start?.toISOString().slice(0, 10),
      period_end: end?.toISOString().slice(0, 10),
    })
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  }
}

async function onDecide(diff: ReconcileDiff, status: ReconcileStatus) {
  try {
    await decide.mutateAsync({ diffId: diffKeyOf(diff, indexOf(diff)), status })
    diff.status = status // optimistic — resolved diffs drop out of "open only"
    toast.add({ severity: 'success', summary: t('reconcile.decided'), life: 2000 })
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  }
}
</script>

<template>
  <section>
    <h2>{{ t('reconcile.title') }}</h2>

    <div class="toolbar">
      <Select
        v-model="portfolioId"
        :options="portfolios"
        option-label="name"
        option-value="portfolioId"
        :placeholder="t('reconcile.portfolio')"
        data-test="rec-portfolio"
      />
      <DatePicker v-model="period" selection-mode="range" date-format="yy-mm-dd" :placeholder="t('reconcile.period')" />
      <Button :label="t('reconcile.run')" :disabled="!portfolioId" data-test="rec-run" @click="run" />
    </div>

    <div v-if="result" class="summary" data-test="rec-summary">
      <Tag :value="t('reconcile.summaryOpen', { n: groups.openCount, total: groups.total })" />
      <label class="inline"
        ><ToggleSwitch v-model="openOnly" data-test="rec-open-only" /> {{ t('reconcile.openOnly') }}</label
      >
    </div>

    <div v-for="kind in DIFF_KINDS" :key="kind" class="kind-group">
      <h3 v-if="groups.byKind[kind].length">
        <Tag :value="t(`reconcile.kind.${kind}`)" :severity="kindSeverity[kind]" />
      </h3>
      <DataTable
        v-if="groups.byKind[kind].length"
        :value="groups.byKind[kind]"
        data-key="diffId"
        :data-test="`rec-rows-${kind}`"
      >
        <Column :header="t('reconcile.system')">
          <template #body="{ data: row }">{{ row.systemTransaction?.quantity ?? '—' }}</template>
        </Column>
        <Column :header="t('reconcile.statement')">
          <template #body="{ data: row }">{{ row.statementTransaction?.quantity ?? '—' }}</template>
        </Column>
        <Column v-if="kind === 'RECON_VALUE_MISMATCH'" :header="t('reconcile.deltas')">
          <template #body="{ data: row }">
            <span v-for="d in row.deltas ?? []" :key="d.field" class="delta">
              {{ d.field }}: {{ d.systemValue }} → {{ d.statementValue }}
            </span>
          </template>
        </Column>
        <Column :header="t('reconcile.status')">
          <template #body="{ data: row }">
            <Tag
              v-if="row.status && row.status !== 'RECON_OPEN'"
              :value="t(`reconcile.st.${row.status}`)"
              severity="secondary"
            />
            <Select
              v-else
              :options="decisionOptions"
              :placeholder="t('reconcile.decide')"
              :data-test="`rec-decide-${indexOf(row)}`"
              @update:model-value="(s: ReconcileStatus) => onDecide(row, s)"
            >
              <template #option="{ option }">{{ t(`reconcile.st.${option}`) }}</template>
            </Select>
          </template>
        </Column>
      </DataTable>
    </div>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 1rem;
  align-items: center;
  margin: 0.75rem 0;
  flex-wrap: wrap;
}
.summary {
  display: flex;
  gap: 1rem;
  align-items: center;
  margin: 0.5rem 0;
}
.inline {
  display: inline-flex;
  gap: 0.5rem;
  align-items: center;
}
.delta {
  display: block;
}
</style>
