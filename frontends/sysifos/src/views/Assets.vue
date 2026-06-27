<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { storeToRefs } from 'pinia'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import { useToast } from 'primevue/usetoast'
import AssetForm from '@/components/forms/AssetForm.vue'
import { useAssets, useCreateAsset, useUpdateAsset } from '@/api/assets'
import { useDictionariesStore } from '@/stores/dictionaries'
import { useSessionStore } from '@/stores/session'
import type { Asset } from '@/api/types'

const { t } = useI18n()
const toast = useToast()
const session = useSessionStore()
const dictionaries = useDictionariesStore()
const { currencies, assetKinds } = storeToRefs(dictionaries)

// Write is gated to midas:admin; everyone else gets a read-only table (design §4.2).
const canWrite = computed(() => session.hasRole('midas:admin'))

const symbol = ref('')
const kind = ref<string | undefined>(undefined)
const params = computed(() => ({ page: 0, size: 50, symbol: symbol.value || undefined, kind: kind.value }))

const { data, isLoading } = useAssets(params)
const rows = computed(() => data.value?.assets ?? [])

const create = useCreateAsset()
const update = useUpdateAsset()

const dialogOpen = ref(false)
const editing = ref<Asset | undefined>(undefined)

onMounted(() => dictionaries.ensureLoaded())

function openCreate() {
  editing.value = undefined
  dialogOpen.value = true
}
function openEdit(a: Asset) {
  editing.value = a
  dialogOpen.value = true
}

async function onSubmit(asset: Asset) {
  try {
    if (asset.assetId) await update.mutateAsync(asset)
    else await create.mutateAsync(asset)
    toast.add({ severity: 'success', summary: t('common.saved'), life: 2000 })
    dialogOpen.value = false
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  }
}
</script>

<template>
  <section>
    <header class="screen-head">
      <h2>{{ t('assets.title') }}</h2>
      <Button
        v-if="canWrite"
        :label="t('common.create')"
        icon="pi pi-plus"
        data-test="new-asset"
        @click="openCreate"
      />
    </header>

    <div class="toolbar">
      <InputText v-model="symbol" :placeholder="t('assets.symbolFilter')" data-test="symbol-filter" />
      <Select
        v-model="kind"
        :options="assetKinds"
        option-label="cs"
        option-value="code"
        show-clear
        :placeholder="t('assets.kind')"
        data-test="kind-filter"
      />
    </div>

    <DataTable :value="rows" :loading="isLoading" data-key="assetId" paginator :rows="50" data-test="assets-table">
      <Column field="symbol" :header="t('assets.symbol')" sortable />
      <Column field="name" :header="t('assets.name')" sortable />
      <Column field="kind" :header="t('assets.kind')" />
      <Column field="currency" :header="t('assets.currency')" />
      <Column field="exchange" :header="t('assets.exchange')" />
      <Column v-if="canWrite" :header="''">
        <template #body="{ data: row }">
          <Button text icon="pi pi-pencil" :aria-label="t('common.edit')" @click="openEdit(row)" />
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="dialogOpen" modal :header="t('assets.title')">
      <AssetForm
        :initial="editing"
        :currencies="currencies"
        :asset-kinds="assetKinds"
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
</style>
