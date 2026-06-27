<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import ToggleSwitch from 'primevue/toggleswitch'
import DatePicker from 'primevue/datepicker'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import { PortfolioFormSchema } from '@/validation/generated'
import type { Client, Portfolio } from '@/api/types'
import type { LabelledEntry } from '@/stores/dictionaries'

const props = defineProps<{ initial?: Portfolio; clients: Client[]; currencies: LabelledEntry[] }>()
const emit = defineEmits<{ submit: [Portfolio]; cancel: [] }>()
const { t } = useI18n()

const portfolioTypes = ['PORTFOLIO_BROKERAGE', 'PORTFOLIO_RETIREMENT', 'PORTFOLIO_OTHER']

const model = reactive({
  clientId: props.initial?.clientId ?? '',
  name: props.initial?.name ?? '',
  baseCurrency: props.initial?.baseCurrency ?? 'CZK',
  portfolioType: props.initial?.portfolioType ?? 'PORTFOLIO_BROKERAGE',
  inceptionDate: props.initial?.inceptionDate ? new Date(props.initial.inceptionDate) : null,
  // S2: track_cash defaults ON (the BFF/contract intent applied here, FE-side).
  trackCash: props.initial?.trackCash ?? true,
})
const errors = ref<Record<string, string>>({})

function submit() {
  const snake = {
    client_id: model.clientId,
    name: model.name,
    base_currency: model.baseCurrency,
    track_cash: model.trackCash,
  }
  const parsed = PortfolioFormSchema.safeParse(snake)
  if (!parsed.success) {
    errors.value = Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message]))
    return
  }
  errors.value = {}
  emit('submit', {
    ...(props.initial?.portfolioId ? { portfolioId: props.initial.portfolioId } : {}),
    clientId: model.clientId,
    name: model.name,
    baseCurrency: model.baseCurrency,
    portfolioType: model.portfolioType as Portfolio['portfolioType'],
    trackCash: model.trackCash,
    inceptionDate: model.inceptionDate ? model.inceptionDate.toISOString() : undefined,
  })
}
</script>

<template>
  <form class="entity-form" @submit.prevent="submit">
    <label>
      {{ t('portfolios.client') }}
      <Select
        v-model="model.clientId"
        :options="clients"
        option-label="name"
        option-value="clientId"
        data-test="portfolio-client"
      />
      <small v-if="errors.client_id" class="err" data-test="err-client">{{ t('common.required') }}</small>
    </label>
    <label>
      {{ t('portfolios.name') }}
      <InputText v-model="model.name" data-test="portfolio-name" />
      <small v-if="errors.name" class="err" data-test="err-name">{{ t('common.required') }}</small>
    </label>
    <label>
      {{ t('portfolios.baseCurrency') }}
      <Select
        v-model="model.baseCurrency"
        :options="currencies"
        option-label="code"
        option-value="code"
        data-test="portfolio-currency"
      />
    </label>
    <label>
      {{ t('portfolios.type') }}
      <Select v-model="model.portfolioType" :options="portfolioTypes" />
    </label>
    <label>
      {{ t('portfolios.inception') }}
      <DatePicker v-model="model.inceptionDate" date-format="yy-mm-dd" />
    </label>
    <label class="inline">
      <ToggleSwitch v-model="model.trackCash" data-test="portfolio-trackcash" />
      {{ t('portfolios.trackCash') }}
      <small class="help">{{ t('portfolios.trackCashHelp') }}</small>
    </label>
    <Tag :value="t('portfolios.fifo')" severity="info" />
    <div class="form-actions">
      <Button type="button" severity="secondary" :label="t('common.cancel')" @click="emit('cancel')" />
      <Button type="submit" :label="t('common.save')" data-test="portfolio-submit" />
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
.entity-form label.inline {
  flex-direction: row;
  align-items: center;
  gap: 0.5rem;
}
.help {
  color: var(--p-text-muted-color, #6b7280);
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
