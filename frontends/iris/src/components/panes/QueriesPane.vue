<script setup lang="ts">
// Phase 4 — Queries pane.
//
// Read-only metadata browser. Fetches the merged catalog from
// `GET /metadata/queries` and renders it in a PrimeVue DataTable with
// sort + per-column filter + pagination.
//
// Lifecycle: lazy fetch on first activation (the pane is mounted by
// the workspace only when the user toggles it on in the View menu, so
// mounting and "first activation" coincide). Subsequent re-mounts via
// dockview tab switching reuse the cached items in memory; the
// Refresh button is the canonical re-fetch.
//
// State persistence: the **sources picker** persists per user
// (`golem.pane.queries.v1.<userId>`); transient sort / column filters
// reset on reload. (Spec-fe-2.1 §11 OPEN-1 → "no" for Queries.)
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import SelectButton from 'primevue/selectbutton'
import Skeleton from 'primevue/skeleton'
import Tag from 'primevue/tag'
import { FilterMatchMode } from '@primevue/core/api'
import { fetchQueries } from '@/services/metadataService'
import { useAuthStore } from '@/stores/auth'
import type {
  QueriesResponse,
  QueryCategory,
  QueryItem,
  QuerySource,
  QuerySourceFilter,
} from '@/types/queries'

const authStore = useAuthStore()
const { t } = useI18n()

// ── Persisted sources picker ───────────────────────────────────────────
// Computed so the labels follow the active locale; values stay as the
// BE-facing identifiers.
const sourceOptions = computed(() => [
  { label: t('panes.queries.sources.local'), value: 'local' as QuerySourceFilter },
  { label: t('panes.queries.sources.platform'), value: 'platform' as QuerySourceFilter },
  { label: t('panes.queries.sources.both'), value: 'both' as QuerySourceFilter },
])
const storageKey = computed(
  () => `golem.pane.queries.v1.${authStore.userId ?? 'anon'}`,
)
const readPersistedSource = (): QuerySourceFilter => {
  try {
    const raw = localStorage.getItem(storageKey.value)
    if (!raw) return 'both'
    const parsed = JSON.parse(raw) as { source?: QuerySourceFilter }
    if (
      parsed.source === 'local' ||
      parsed.source === 'platform' ||
      parsed.source === 'both'
    ) {
      return parsed.source
    }
    return 'both'
  } catch {
    return 'both'
  }
}
const sourceFilter = ref<QuerySourceFilter>(readPersistedSource())
const persistSource = () => {
  try {
    localStorage.setItem(
      storageKey.value,
      JSON.stringify({ source: sourceFilter.value }),
    )
  } catch {
    /* ignore quota errors */
  }
}

// ── Items + lifecycle ──────────────────────────────────────────────────
const items = ref<QueryItem[]>([])
const warnings = ref<string[]>([])
const loading = ref(false)
const errorMessage = ref<string | null>(null)
const dismissedWarnings = ref(false)

const counts = computed(() => {
  let local = 0
  let platform = 0
  for (const it of items.value) {
    if (it.source === 'local') local += 1
    else platform += 1
  }
  return { total: items.value.length, local, platform }
})

const load = async () => {
  loading.value = true
  errorMessage.value = null
  dismissedWarnings.value = false
  try {
    const response: QueriesResponse = await fetchQueries(sourceFilter.value)
    items.value = response.items
    warnings.value = response.warnings
  } catch (err) {
    errorMessage.value =
      err instanceof Error ? err.message : t('panes.queries.errorFetch')
    items.value = []
    warnings.value = []
  } finally {
    loading.value = false
  }
}

watch(sourceFilter, async () => {
  persistSource()
  await load()
})

onMounted(() => {
  void load()
})

// ── DataTable filter setup ─────────────────────────────────────────────
// Per-column "global" filters use FilterMatchMode.CONTAINS by default
// for the freeform Name / Pattern / Description; Category / Source use
// FilterMatchMode.EQUALS via the column-header tag picker.
const filters = ref({
  name: { value: null as string | null, matchMode: FilterMatchMode.CONTAINS },
  pattern: { value: null as string | null, matchMode: FilterMatchMode.CONTAINS },
  description: { value: null as string | null, matchMode: FilterMatchMode.CONTAINS },
  category: { value: null as QueryCategory | null, matchMode: FilterMatchMode.EQUALS },
  source: { value: null as QuerySource | null, matchMode: FilterMatchMode.EQUALS },
})

// Tag-picker options for the Category / Source row filters. Computed
// so labels follow the active locale.
const categoryOptions = computed(() => [
  { label: t('panes.queries.categories.pattern'), value: 'pattern' as QueryCategory },
  { label: t('panes.queries.categories.sql_query'), value: 'sql_query' as QueryCategory },
  { label: t('panes.queries.categories.procedure'), value: 'procedure' as QueryCategory },
  { label: t('panes.queries.categories.entity'), value: 'entity' as QueryCategory },
  { label: t('panes.queries.categories.table'), value: 'table' as QueryCategory },
])
const sourceTagOptions = computed(() => [
  { label: t('panes.queries.sources.local'), value: 'local' as QuerySource },
  { label: t('panes.queries.sources.platform'), value: 'platform' as QuerySource },
])

const categoryLabel = (cat: QueryCategory): string =>
  t(`panes.queries.categories.${cat}`)

// PrimeVue Tag severities for category — keep them visually distinct.
const categorySeverity = (cat: QueryCategory): string => {
  switch (cat) {
    case 'pattern':
      return 'info'
    case 'sql_query':
      return 'success'
    case 'procedure':
      return 'warn'
    case 'entity':
      return 'secondary'
    case 'table':
      return 'contrast'
  }
}
</script>

<template>
  <div class="queries-pane">
    <div class="queries-toolbar">
      <SelectButton
        v-model="sourceFilter"
        :options="sourceOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        size="small"
        :aria-label="t('panes.queries.sourcesAria')"
      />
      <Button
        text
        plain
        size="small"
        icon="pi pi-refresh"
        :label="t('panes.queries.refresh')"
        :disabled="loading"
        :aria-label="t('panes.queries.refreshAria')"
        @click="load"
      />
      <span class="queries-count" aria-live="polite">
        <template v-if="loading">{{ t('panes.queries.loading') }}</template>
        <template v-else>
          {{ t('panes.queries.count', { count: counts.total, local: counts.local, platform: counts.platform }) }}
        </template>
      </span>
    </div>

    <Message
      v-if="warnings.length > 0 && !dismissedWarnings"
      severity="warn"
      :closable="true"
      class="queries-warnings"
      @close="dismissedWarnings = true"
    >
      <strong>{{ t('panes.queries.warningsHeading') }}</strong>
      <ul class="queries-warnings-list">
        <li v-for="w in warnings" :key="w">{{ w }}</li>
      </ul>
    </Message>

    <Message
      v-if="errorMessage"
      severity="error"
      :closable="false"
      class="queries-warnings"
    >
      {{ errorMessage }}
    </Message>

    <div class="queries-table">
      <Skeleton v-if="loading && items.length === 0" height="100%" />
      <DataTable
        v-else
        :value="items"
        v-model:filters="filters"
        :paginator="true"
        :rows="50"
        :rows-per-page-options="[25, 50, 100, 200]"
        size="small"
        sort-mode="single"
        filter-display="row"
        striped-rows
        :scrollable="true"
        scroll-height="flex"
        class="p-datatable-sm"
        data-key="id"
      >
        <template #empty>
          <div class="queries-empty">
            {{ t('panes.queries.empty') }}
          </div>
        </template>

        <Column
          field="name"
          :header="t('panes.queries.columns.name')"
          :sortable="true"
          filter-field="name"
          :show-filter-menu="false"
          :show-clear-button="false"
        >
          <template #filter="{ filterModel, filterCallback }">
            <InputText
              :model-value="filterModel.value"
              size="small"
              :placeholder="t('panes.queries.filterPlaceholder')"
              @update:model-value="(v) => { filterModel.value = v; filterCallback() }"
            />
          </template>
        </Column>

        <Column
          field="pattern"
          :header="t('panes.queries.columns.pattern')"
          :sortable="true"
          filter-field="pattern"
          :show-filter-menu="false"
          :show-clear-button="false"
        >
          <template #body="{ data }">
            <span v-if="data.pattern" class="queries-pattern">{{ data.pattern }}</span>
            <span v-else class="queries-dim">—</span>
          </template>
          <template #filter="{ filterModel, filterCallback }">
            <InputText
              :model-value="filterModel.value"
              size="small"
              :placeholder="t('panes.queries.filterPlaceholder')"
              @update:model-value="(v) => { filterModel.value = v; filterCallback() }"
            />
          </template>
        </Column>

        <Column
          field="description"
          :header="t('panes.queries.columns.description')"
          :sortable="true"
          filter-field="description"
          :show-filter-menu="false"
          :show-clear-button="false"
        >
          <template #filter="{ filterModel, filterCallback }">
            <InputText
              :model-value="filterModel.value"
              size="small"
              :placeholder="t('panes.queries.filterPlaceholder')"
              @update:model-value="(v) => { filterModel.value = v; filterCallback() }"
            />
          </template>
        </Column>

        <Column
          field="category"
          :header="t('panes.queries.columns.category')"
          :sortable="true"
          filter-field="category"
          :show-filter-menu="false"
          :show-clear-button="false"
          style="width: 8rem"
        >
          <template #body="{ data }">
            <Tag
              :value="categoryLabel(data.category)"
              :severity="categorySeverity(data.category)"
            />
          </template>
          <template #filter="{ filterModel, filterCallback }">
            <SelectButton
              :model-value="filterModel.value"
              :options="categoryOptions"
              option-label="label"
              option-value="value"
              :allow-empty="true"
              size="small"
              class="queries-filter-select"
              @update:model-value="(v) => { filterModel.value = v; filterCallback() }"
            />
          </template>
        </Column>

        <Column
          field="source"
          :header="t('panes.queries.columns.source')"
          :sortable="true"
          filter-field="source"
          :show-filter-menu="false"
          :show-clear-button="false"
          style="width: 7rem"
        >
          <template #body="{ data }">
            <Tag
              :value="data.source === 'local' ? t('panes.queries.sources.local') : t('panes.queries.sources.platform')"
              :severity="data.source === 'local' ? 'danger' : 'secondary'"
            />
          </template>
          <template #filter="{ filterModel, filterCallback }">
            <SelectButton
              :model-value="filterModel.value"
              :options="sourceTagOptions"
              option-label="label"
              option-value="value"
              :allow-empty="true"
              size="small"
              class="queries-filter-select"
              @update:model-value="(v) => { filterModel.value = v; filterCallback() }"
            />
          </template>
        </Column>
      </DataTable>
    </div>
  </div>
</template>

<style scoped>
.queries-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  background-color: var(--p-surface-50);
}

.queries-toolbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0.625rem;
  border-bottom: 1px solid var(--p-surface-200);
  background-color: #fff;
  flex-shrink: 0;
}

.queries-count {
  margin-left: auto;
  font-size: 0.7rem;
  color: var(--p-surface-500);
  font-variant-numeric: tabular-nums;
}

.queries-warnings {
  margin: 0.5rem 0.625rem 0;
}

.queries-warnings-list {
  margin: 0.25rem 0 0 1.25rem;
  padding: 0;
  font-size: 0.75rem;
}

.queries-table {
  flex: 1;
  min-height: 0;
  padding: 0.5rem 0.625rem;
  overflow: hidden;
}

.queries-pattern {
  font-family:
    ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono',
    'Courier New', monospace;
  font-size: 0.78rem;
}

.queries-dim {
  color: var(--p-surface-400);
}

.queries-empty {
  padding: 1.25rem;
  text-align: center;
  font-size: 0.85rem;
  color: var(--p-surface-500);
  font-style: italic;
}

.queries-filter-select :deep(.p-button) {
  padding-block: 0.2rem;
}
</style>
