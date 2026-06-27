<script setup lang="ts">
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import { useToast } from 'primevue/usetoast'
import { storeToRefs } from 'pinia'
import AssetForm from '@/components/forms/AssetForm.vue'
import { useCreateAsset } from '@/api/assets'
import { useDictionariesStore } from '@/stores/dictionaries'
import type { Asset } from '@/api/types'

/**
 * S6 quick-create (design §5.4): opened mid-entry from an asset typeahead when a
 * symbol is unknown. The typed symbol is prefilled; on submit the new asset is
 * written synchronously and returned to the caller so entry resumes without losing
 * the surrounding form/grid state. Reusable from the transaction form, the bulk
 * grid (2.3), and import preview (2.5).
 */
const visible = defineModel<boolean>('visible', { required: true })
const props = defineProps<{ symbol?: string }>()
const emit = defineEmits<{ created: [Asset] }>()

const { t } = useI18n()
const toast = useToast()
const dictionaries = useDictionariesStore()
const { currencies, assetKinds } = storeToRefs(dictionaries)
const create = useCreateAsset()

onMounted(() => dictionaries.ensureLoaded())

async function onSubmit(asset: Asset) {
  try {
    const res = await create.mutateAsync(asset)
    const created = res.asset ?? asset
    toast.add({ severity: 'success', summary: t('assets.created'), life: 2000 })
    visible.value = false
    emit('created', created)
  } catch {
    toast.add({ severity: 'error', summary: t('common.loadFailed'), life: 3000 })
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" modal :header="t('assets.quickCreate')" data-test="asset-quick-create">
    <AssetForm
      :initial="{ symbol: props.symbol }"
      :currencies="currencies"
      :asset-kinds="assetKinds"
      @submit="onSubmit"
      @cancel="visible = false"
    />
  </Dialog>
</template>
