<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import DatePicker from 'primevue/datepicker'
import Tag from 'primevue/tag'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import { useAudit } from '@/api/audit'
import { diffKeys, prettyJson } from '@/validation/audit'
import type { AuditEntry } from '@/api/types'

const { t } = useI18n()

const entityType = ref<string | undefined>(undefined)
const actor = ref('')
const range = ref<Date[] | null>(null)
const entityTypes = ['client', 'portfolio', 'asset', 'transaction']

const params = computed(() => {
  const [from, to] = range.value ?? []
  return {
    entity_type: entityType.value,
    actor_user_id: actor.value || undefined,
    from: from?.toISOString().slice(0, 10),
    to: to?.toISOString().slice(0, 10),
  }
})
const { data, isLoading } = useAudit(params)
const rows = computed(() => data.value?.entries ?? [])

const opSeverity: Record<string, string> = {
  CREATE: 'success',
  UPDATE: 'info',
  ARCHIVE: 'warn',
  REVERSE: 'danger',
  DELETE: 'danger',
}

const detail = ref<AuditEntry | null>(null)
const detailOpen = ref(false)
function open(entry: AuditEntry) {
  detail.value = entry
  detailOpen.value = true
}
const changed = computed(() => (detail.value ? diffKeys(detail.value.beforeJsonb, detail.value.afterJsonb) : new Set<string>()))
const traceUrl = (traceId?: string) => (traceId ? `/grafana/trace/${traceId}` : undefined)
</script>

<template>
  <section>
    <h2>{{ t('audit.title') }}</h2>

    <div class="toolbar">
      <Select
        v-model="entityType"
        :options="entityTypes"
        show-clear
        :placeholder="t('audit.entityType')"
        data-test="audit-entity"
      />
      <InputText v-model="actor" :placeholder="t('audit.actor')" data-test="audit-actor" />
      <DatePicker v-model="range" selection-mode="range" date-format="yy-mm-dd" :placeholder="t('audit.range')" />
    </div>

    <DataTable :value="rows" :loading="isLoading" data-key="auditId" paginator :rows="50" data-test="audit-table">
      <Column field="occurredAt" :header="t('audit.when')" sortable />
      <Column field="actorUserId" :header="t('audit.actor')" />
      <Column field="entityType" :header="t('audit.entityType')" />
      <Column :header="t('audit.operation')">
        <template #body="{ data: row }">
          <Tag :value="row.operation" :severity="opSeverity[row.operation] ?? 'secondary'" />
        </template>
      </Column>
      <Column :header="''">
        <template #body="{ data: row }">
          <Button text icon="pi pi-search" :aria-label="t('audit.view')" @click="open(row)" />
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="detailOpen" modal :header="t('audit.changeTitle')" :style="{ width: '70vw' }" data-test="audit-detail">
      <div v-if="detail">
        <p v-if="detail.traceId">
          <a :href="traceUrl(detail.traceId)" target="_blank" data-test="audit-trace">{{ t('audit.trace') }}</a>
        </p>
        <div class="diff-grid">
          <div>
            <h4>{{ t('audit.before') }}</h4>
            <pre data-test="audit-before">{{ prettyJson(detail.beforeJsonb) }}</pre>
          </div>
          <div>
            <h4>{{ t('audit.after') }}</h4>
            <pre data-test="audit-after">{{ prettyJson(detail.afterJsonb) }}</pre>
          </div>
        </div>
        <p class="changed">{{ t('audit.changed', { fields: [...changed].join(', ') || '—' }) }}</p>
      </div>
    </Dialog>
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
.diff-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}
.diff-grid pre {
  background: var(--p-content-background, #f8f9fa);
  padding: 0.75rem;
  border-radius: 6px;
  overflow: auto;
  max-height: 50vh;
}
.changed {
  margin-top: 1rem;
  font-weight: 600;
}
</style>
