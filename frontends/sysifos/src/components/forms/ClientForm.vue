<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import { ClientFormSchema } from '@/validation/generated'
import type { Client } from '@/api/types'

const props = defineProps<{ initial?: Client }>()
const emit = defineEmits<{ submit: [Client]; cancel: [] }>()
const { t } = useI18n()

const model = reactive({
  name: props.initial?.name ?? '',
  email: props.initial?.contactEmail ?? '',
  phone: props.initial?.contactPhone ?? '',
})
const errors = ref<Record<string, string>>({})

function submit() {
  const snake = {
    name: model.name,
    contact_email: model.email || undefined,
    contact_phone: model.phone || undefined,
  }
  const parsed = ClientFormSchema.safeParse(snake)
  if (!parsed.success) {
    errors.value = Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message]))
    return
  }
  errors.value = {}
  emit('submit', {
    ...(props.initial?.clientId ? { clientId: props.initial.clientId } : {}),
    name: model.name,
    contactEmail: model.email || undefined,
    contactPhone: model.phone || undefined,
  })
}
</script>

<template>
  <form class="entity-form" @submit.prevent="submit">
    <label>
      {{ t('clients.name') }}
      <InputText v-model="model.name" data-test="client-name" />
      <small v-if="errors.name" class="err" data-test="err-name">{{ t('common.required') }}</small>
    </label>
    <label>
      {{ t('clients.email') }}
      <InputText v-model="model.email" data-test="client-email" />
      <small v-if="errors.contact_email" class="err" data-test="err-email">{{ t('common.invalidEmail') }}</small>
    </label>
    <label>
      {{ t('clients.phone') }}
      <InputText v-model="model.phone" data-test="client-phone" />
    </label>
    <div class="form-actions">
      <Button type="button" severity="secondary" :label="t('common.cancel')" @click="emit('cancel')" />
      <Button type="submit" :label="t('common.save')" data-test="client-submit" />
    </div>
  </form>
</template>

<style scoped>
.entity-form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-width: 22rem;
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
