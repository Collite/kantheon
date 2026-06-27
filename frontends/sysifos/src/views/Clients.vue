<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Tag from 'primevue/tag'
import ToggleSwitch from 'primevue/toggleswitch'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import ClientForm from '@/components/forms/ClientForm.vue'
import { useClients, useCreateClient, useUpdateClient, useArchiveClient } from '@/api/clients'
import type { Client } from '@/api/types'

const { t } = useI18n()
const toast = useToast()
const confirm = useConfirm()

const includeArchived = ref(false)
const namePrefix = ref('')
const params = computed(() => ({
  page: 0,
  size: 50,
  status: includeArchived.value ? undefined : 'CLIENT_ACTIVE',
  name_prefix: namePrefix.value || undefined,
}))

const { data, isLoading } = useClients(params)
const rows = computed(() => data.value?.clients ?? [])

const create = useCreateClient()
const update = useUpdateClient()
const archive = useArchiveClient()

const dialogOpen = ref(false)
const editing = ref<Client | undefined>(undefined)

function openCreate() {
  editing.value = undefined
  dialogOpen.value = true
}
function openEdit(c: Client) {
  editing.value = c
  dialogOpen.value = true
}

async function onSubmit(client: Client) {
  try {
    if (client.clientId) await update.mutateAsync(client)
    else await create.mutateAsync(client)
    toast.add({ severity: 'success', summary: t('common.saved'), life: 2000 })
    dialogOpen.value = false
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  }
}

function onArchive(c: Client) {
  confirm.require({
    message: t('common.confirmArchive', { entity: t('clients.entity') }),
    accept: async () => {
      await archive.mutateAsync(c.clientId!)
      toast.add({ severity: 'success', summary: t('common.archived'), life: 2000 })
    },
  })
}
</script>

<template>
  <section>
    <header class="screen-head">
      <h2>{{ t('clients.title') }}</h2>
      <Button :label="t('common.create')" icon="pi pi-plus" data-test="new-client" @click="openCreate" />
    </header>

    <div class="toolbar">
      <InputText v-model="namePrefix" :placeholder="t('clients.nameFilter')" data-test="name-filter" />
      <label class="inline"><ToggleSwitch v-model="includeArchived" /> {{ t('common.includeArchived') }}</label>
    </div>

    <DataTable :value="rows" :loading="isLoading" data-key="clientId" paginator :rows="50" data-test="clients-table">
      <Column field="name" :header="t('clients.name')" sortable />
      <Column field="contactEmail" :header="t('clients.email')" />
      <Column field="contactPhone" :header="t('clients.phone')" />
      <Column :header="''">
        <template #body="{ data: row }">
          <Tag v-if="row.status === 'CLIENT_ARCHIVED'" :value="t('common.archived')" severity="secondary" />
          <Button text icon="pi pi-pencil" :aria-label="t('common.edit')" @click="openEdit(row)" />
          <Button
            v-if="row.status !== 'CLIENT_ARCHIVED'"
            text
            icon="pi pi-inbox"
            :aria-label="t('common.archive')"
            @click="onArchive(row)"
          />
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="dialogOpen" modal :header="t('clients.title')">
      <ClientForm :initial="editing" @submit="onSubmit" @cancel="dialogOpen = false" />
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
