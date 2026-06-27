<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Select from 'primevue/select'
import DatePicker from 'primevue/datepicker'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import { TransactionFormSchema } from '@/validation/generated'
import AssetQuickCreate from '@/components/forms/AssetQuickCreate.vue'
import type { Asset, Transaction } from '@/api/types'
import type { LabelledEntry } from '@/stores/dictionaries'

const props = defineProps<{
  portfolioId: string
  initial?: Transaction
  assets: Asset[]
  currencies: LabelledEntry[]
  /** Security-leg kinds only; derived TX_CASH_* are not user-entered. */
  transactionKinds: LabelledEntry[]
}>()
const emit = defineEmits<{ submit: [Transaction]; cancel: [] }>()
const { t } = useI18n()

// Local asset list so a quick-created asset appears immediately in the typeahead.
const localAssets = ref<Asset[]>([...props.assets])
const assetOptions = computed(() => localAssets.value)

const model = reactive({
  assetId: props.initial?.assetId ?? '',
  kind: props.initial?.kind ?? 'TX_BUY',
  tradeDate: props.initial?.tradeDate ? new Date(props.initial.tradeDate) : new Date('2026-06-24'),
  settleDate: props.initial?.settleDate ? new Date(props.initial.settleDate) : null,
  quantity: props.initial?.quantity ?? '',
  price: props.initial?.price?.amount ?? '',
  fee: props.initial?.fee?.amount ?? '',
  currency: props.initial?.currency ?? 'CZK',
})
const errors = ref<Record<string, string>>({})

// Quick-create wiring: the typed symbol filter seeds a new asset when unknown.
const quickOpen = ref(false)
const assetFilter = ref('')
const knownSymbol = computed(() =>
  localAssets.value.some((a) => a.symbol.toLowerCase() === assetFilter.value.trim().toLowerCase()),
)
function onAssetCreated(asset: Asset) {
  localAssets.value = [asset, ...localAssets.value]
  if (asset.assetId) model.assetId = asset.assetId
}

function toIso(d: Date | null): string | undefined {
  return d ? d.toISOString().slice(0, 10) : undefined
}

function submit() {
  const parsed = TransactionFormSchema.safeParse({
    portfolio_id: props.portfolioId,
    asset_id: model.assetId,
    kind: model.kind,
    trade_date: toIso(model.tradeDate) ?? '',
    settle_date: toIso(model.settleDate),
    quantity: model.quantity,
    price: { amount: model.price },
    fee: { amount: model.fee || undefined },
    currency: model.currency,
  })
  if (!parsed.success) {
    errors.value = Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message]))
    return
  }
  errors.value = {}
  emit('submit', {
    ...(props.initial?.transactionId ? { transactionId: props.initial.transactionId } : {}),
    portfolioId: props.portfolioId,
    assetId: model.assetId,
    kind: model.kind,
    tradeDate: toIso(model.tradeDate),
    settleDate: toIso(model.settleDate),
    quantity: model.quantity,
    price: { amount: model.price },
    ...(model.fee ? { fee: { amount: model.fee } } : {}),
    currency: model.currency,
  })
}
</script>

<template>
  <form class="entity-form" @submit.prevent="submit">
    <label>
      {{ t('transactions.asset') }}
      <Select
        v-model="model.assetId"
        :options="assetOptions"
        option-label="symbol"
        option-value="assetId"
        filter
        :filter-placeholder="t('transactions.assetFilter')"
        data-test="tx-asset"
        @filter="assetFilter = $event.value"
      >
        <template #footer>
          <Button
            v-if="assetFilter && !knownSymbol"
            text
            icon="pi pi-plus"
            :label="t('transactions.createAsset', { symbol: assetFilter })"
            data-test="tx-create-asset"
            @click="quickOpen = true"
          />
        </template>
      </Select>
      <small v-if="errors.asset_id" class="err" data-test="err-asset">{{ t('common.required') }}</small>
    </label>
    <label>
      {{ t('transactions.kind') }}
      <Select
        v-model="model.kind"
        :options="transactionKinds"
        option-label="cs"
        option-value="code"
        data-test="tx-kind"
      />
    </label>
    <label>
      {{ t('transactions.tradeDate') }}
      <DatePicker v-model="model.tradeDate" date-format="yy-mm-dd" data-test="tx-trade-date" />
    </label>
    <label>
      {{ t('transactions.settleDate') }}
      <DatePicker v-model="model.settleDate" date-format="yy-mm-dd" />
    </label>
    <label>
      {{ t('transactions.quantity') }}
      <InputText v-model="model.quantity" data-test="tx-quantity" />
      <small v-if="errors.quantity" class="err" data-test="err-quantity">{{ t('transactions.invalidQuantity') }}</small>
    </label>
    <label>
      {{ t('transactions.price') }}
      <InputText v-model="model.price" data-test="tx-price" />
      <small v-if="errors.price" class="err" data-test="err-price">{{ t('common.required') }}</small>
    </label>
    <label>
      {{ t('transactions.fee') }}
      <InputText v-model="model.fee" data-test="tx-fee" />
    </label>
    <label>
      {{ t('transactions.currency') }}
      <Select
        v-model="model.currency"
        :options="currencies"
        option-label="code"
        option-value="code"
        data-test="tx-currency"
      />
    </label>
    <div class="form-actions">
      <Button type="button" severity="secondary" :label="t('common.cancel')" @click="emit('cancel')" />
      <Button type="submit" :label="t('common.save')" data-test="tx-submit" />
    </div>

    <AssetQuickCreate v-model:visible="quickOpen" :symbol="assetFilter" @created="onAssetCreated" />
  </form>
</template>

<style scoped>
.entity-form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-width: 26rem;
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
