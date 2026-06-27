<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Button from 'primevue/button'
import { AssetFormSchema } from '@/validation/generated'
import type { Asset, AssetKind } from '@/api/types'
import type { LabelledEntry } from '@/stores/dictionaries'

const props = defineProps<{
  initial?: Partial<Asset>
  currencies: LabelledEntry[]
  assetKinds: LabelledEntry[]
}>()
const emit = defineEmits<{ submit: [Asset]; cancel: [] }>()
const { t } = useI18n()

const model = reactive({
  symbol: props.initial?.symbol ?? '',
  name: props.initial?.name ?? '',
  kind: (props.initial?.kind ?? 'ASSET_STOCK') as AssetKind,
  currency: props.initial?.currency ?? 'CZK',
  isin: props.initial?.isin ?? '',
  exchange: props.initial?.exchange ?? '',
})
const errors = ref<Record<string, string>>({})

function submit() {
  const parsed = AssetFormSchema.safeParse({
    symbol: model.symbol,
    name: model.name,
    kind: model.kind,
    currency: model.currency,
  })
  if (!parsed.success) {
    errors.value = Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message]))
    return
  }
  errors.value = {}
  emit('submit', {
    ...(props.initial?.assetId ? { assetId: props.initial.assetId } : {}),
    symbol: model.symbol,
    name: model.name,
    kind: model.kind,
    currency: model.currency,
    isin: model.isin || undefined,
    exchange: model.exchange || undefined,
  })
}
</script>

<template>
  <form class="entity-form" @submit.prevent="submit">
    <label>
      {{ t('assets.symbol') }}
      <InputText v-model="model.symbol" data-test="asset-symbol" />
      <small v-if="errors.symbol" class="err" data-test="err-symbol">{{ t('common.required') }}</small>
    </label>
    <label>
      {{ t('assets.name') }}
      <InputText v-model="model.name" data-test="asset-name" />
      <small v-if="errors.name" class="err" data-test="err-name">{{ t('common.required') }}</small>
    </label>
    <label>
      {{ t('assets.kind') }}
      <Select
        v-model="model.kind"
        :options="assetKinds"
        option-label="cs"
        option-value="code"
        data-test="asset-kind"
      />
    </label>
    <label>
      {{ t('assets.currency') }}
      <Select
        v-model="model.currency"
        :options="currencies"
        option-label="code"
        option-value="code"
        data-test="asset-currency"
      />
    </label>
    <label>
      {{ t('assets.isin') }}
      <InputText v-model="model.isin" data-test="asset-isin" />
    </label>
    <label>
      {{ t('assets.exchange') }}
      <InputText v-model="model.exchange" data-test="asset-exchange" />
    </label>
    <div class="form-actions">
      <Button type="button" severity="secondary" :label="t('common.cancel')" @click="emit('cancel')" />
      <Button type="submit" :label="t('common.save')" data-test="asset-submit" />
    </div>
  </form>
</template>

<style scoped>
.entity-form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-width: 24rem;
}
.entity-form label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.err {
  color: var(--p-red-500, #ef4444);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
</style>
