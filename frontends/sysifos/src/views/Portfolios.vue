<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Tag from 'primevue/tag'
import ToggleSwitch from 'primevue/toggleswitch'
import Select from 'primevue/select'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import PortfolioForm from '@/components/forms/PortfolioForm.vue'
import { usePortfolios, useCreatePortfolio, useUpdatePortfolio, useArchivePortfolio } from '@/api/portfolios'
import { useClients } from '@/api/clients'
import { useDictionariesStore } from '@/stores/dictionaries'
import type { Portfolio } from '@/api/types'

const { t } = useI18n()
const toast = useToast()
const confirm = useConfirm()
const dictionaries = useDictionariesStore()

const includeArchived = ref(false)
const clientFilter = ref<string | undefined>(undefined)

const clientParams = computed(() => ({ page: 0, size: 200, status: 'CLIENT_ACTIVE' }))
const { data: clientData } = useClients(clientParams)
const clients = computed(() => clientData.value?.clients ?? [])

const params = computed(() => ({
  page: 0,
  size: 50,
  status: includeArchived.value ? undefined : 'PORTFOLIO_ACTIVE',
  client_id: clientFilter.value || undefined,
}))
const { data, isLoading } = usePortfolios(params)
const rows = computed(() => data.value?.portfolios ?? [])

const create = useCreatePortfolio()
const update = useUpdatePortfolio()
const archive = useArchivePortfolio()

const dialogOpen = ref(false)
const editing = ref<Portfolio | undefined>(undefined)

onMounted(() => dictionaries.ensureLoaded())

function openCreate() {
  editing.value = undefined
  dialogOpen.value = true
}
function openEdit(p: Portfolio) {
  editing.value = p
  dialogOpen.value = true
}

async function onSubmit(portfolio: Portfolio) {
  try {
    if (portfolio.portfolioId) await update.mutateAsync(portfolio)
    else await create.mutateAsync(portfolio)
    toast.add({ severity: 'success', summary: t('common.saved'), life: 2000 })
    dialogOpen.value = false
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  }
}

function onArchive(p: Portfolio) {
  confirm.require({
    message: t('common.confirmArchive', { entity: t('portfolios.entity') }),
    accept: async () => {
      await archive.mutateAsync(p.portfolioId!)
      toast.add({ severity: 'success', summary: t('common.archived'), life: 2000 })
    },
  })
}
</script>

<template>
  <section>
    <header class="screen-head">
      <h2>{{ t('portfolios.title') }}</h2>
      <Button :label="t('common.create')" icon="pi pi-plus" data-test="new-portfolio" @click="openCreate" />
    </header>

    <div class="toolbar">
      <Select
        v-model="clientFilter"
        :options="clients"
        option-label="name"
        option-value="clientId"
        :placeholder="t('portfolios.client')"
        show-clear
        data-test="client-filter"
      />
      <label class="inline"><ToggleSwitch v-model="includeArchived" /> {{ t('common.includeArchived') }}</label>
    </div>

    <DataTable :value="rows" :loading="isLoading" data-key="portfolioId" paginator :rows="50" data-test="portfolios-table">
      <Column field="name" :header="t('portfolios.name')" sortable />
      <Column field="baseCurrency" :header="t('portfolios.baseCurrency')" />
      <Column :header="t('portfolios.trackCash')">
        <template #body="{ data: row }">
          <Tag :value="row.trackCash ? '✓' : '—'" :severity="row.trackCash ? 'success' : 'secondary'" />
        </template>
      </Column>
      <Column :header="''">
        <template #body="{ data: row }">
          <Tag v-if="row.status === 'PORTFOLIO_ARCHIVED'" :value="t('common.archived')" severity="secondary" />
          <Button text icon="pi pi-pencil" :aria-label="t('common.edit')" @click="openEdit(row)" />
          <Button
            v-if="row.status !== 'PORTFOLIO_ARCHIVED'"
            text
            icon="pi pi-inbox"
            :aria-label="t('common.archive')"
            @click="onArchive(row)"
          />
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="dialogOpen" modal :header="t('portfolios.title')">
      <PortfolioForm
        :initial="editing"
        :clients="clients"
        :currencies="dictionaries.currencies"
        @submit="onSubmit"
        @cancel="dialogOpen = false"
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
.toolbar {
  display: flex;
  gap: 1rem;
  align-items: center;
  margin: 0.75rem 0;
}
.inline {
  display: inline-flex;
  gap: 0.5rem;
  align-items: center;
}
</style>
