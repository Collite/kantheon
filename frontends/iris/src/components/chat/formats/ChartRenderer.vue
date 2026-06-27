<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import embed from 'vega-embed'
import type { Result } from 'vega-embed'
import Button from 'primevue/button'
import SelectButton from 'primevue/selectbutton'
import MultiSelect from 'primevue/multiselect'
import ToggleButton from 'primevue/togglebutton'
import ContextMenu from 'primevue/contextmenu'
import { useToast } from 'primevue/usetoast'
import { FormatKind, parseJsonField } from '@/types/envelope'
import type {
  ChartIntent,
  ChartIntentDetails,
  Drilldown,
} from '@/types/envelope'
import { compileVegaLite } from './compileVegaLite'
import { deriveAutoIntent, projectChartData } from './chartAutoIntent'

interface Props {
  text?: string
  content?: unknown
  details?: ChartIntentDetails
  drilldowns?: Drilldown[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'display-changed', state: { viewKind: FormatKind }): void
}>()

const toast = useToast()
const { t } = useI18n()

const containerRef = ref<HTMLElement | null>(null)
const vegaViewRef = ref<Result['view'] | null>(null)

const rows = computed<Record<string, unknown>[]>(() => {
  // Prefer the envelope's top-level rows (`content` = parsed contentJson). Fall
  // back to the chart details' own embedded rows (envelope/v1 `rowsJson`, Rule-7)
  // for the "loose" chart shape, where the data rides under format.chart and the
  // top-level content is absent/null (fixture 04-chart-loose).
  const src = Array.isArray(props.content)
    ? props.content
    : parseJsonField(props.details?.rowsJson)
  if (!Array.isArray(src)) return []
  return (src as unknown[]).filter(
    (r): r is Record<string, unknown> => !!r && typeof r === 'object',
  )
})

const intent = computed<ChartIntent | null>(() => props.details?.intent ?? null)

const vegaLiteSpec = computed<Record<string, unknown> | null>(() => {
  // envelope/v1 carries the spec as a Rule-7 JSON string (`vegaLiteSpecJson`).
  const parsed = parseJsonField(props.details?.vegaLiteSpecJson)
  return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null
})

// Client-side fallback: when the user flips a *table* to a chart (the
// "Show as graph" icon), the backend never attached a chart intent/spec —
// ChatBubble suppresses the table's details for the chart renderer — so we
// synthesise one from the raw rows. (Pure logic lives in ./chartAutoIntent.)
const autoIntent = computed<ChartIntent | null>(() =>
  intent.value ? null : deriveAutoIntent(rows.value),
)

// The intent that actually drives the chart: the backend's, or the auto one.
const baseIntent = computed<ChartIntent | null>(() => intent.value ?? autoIntent.value)

// Data fed to the compiler. Reuse the backend spec's embedded data when present;
// otherwise project the rows down to x + numeric series (coercing blanks to null
// gaps), keeping the x column's original value for labels.
const chartData = computed<Record<string, unknown>[]>(() => {
  const bi = baseIntent.value
  if (!bi) return []
  const embedded = (vegaLiteSpec.value as { data?: { values?: unknown[] } } | null)?.data?.values
  if (embedded) return embedded as Record<string, unknown>[]
  return projectChartData(rows.value, bi)
})

const overrideKind = ref<string | null>(null)
const overrideStacked = ref<boolean | null>(null)
const hiddenSeriesNames = ref<Set<string>>(new Set())

const chartKind = computed<string>(() => {
  if (overrideKind.value) return overrideKind.value
  return baseIntent.value?.kind ?? 'line'
})

const stacked = computed<boolean>(() => {
  if (overrideStacked.value !== null) return overrideStacked.value
  return baseIntent.value?.stacked ?? false
})

const allSeriesNames = computed<string[]>(() => baseIntent.value?.y ?? [])

const visibleSeriesNames = computed({
  get: () => {
    const hidden = new Set(hiddenSeriesNames.value)
    return allSeriesNames.value.filter((n) => !hidden.has(n))
  },
  set: (next: string[]) => {
    const wanted = new Set(next)
    hiddenSeriesNames.value = new Set(
      allSeriesNames.value.filter((n) => !wanted.has(n)),
    )
  },
})

const seriesOptions = computed(() =>
  allSeriesNames.value.map((name) => ({ label: name, value: name })),
)

const typeOptions = computed(() => [
  { label: t('chart.typeLine'), value: 'line', icon: 'pi pi-chart-line' },
  { label: t('chart.typeBar'), value: 'bar', icon: 'pi pi-chart-bar' },
  { label: t('chart.typePie'), value: 'pie', icon: 'pi pi-chart-pie' },
])

const effectiveSpec = computed<Record<string, unknown>>(() => {
  const bi = baseIntent.value
  if (!bi) return {}
  const noOverrides =
    overrideKind.value === null &&
    overrideStacked.value === null &&
    hiddenSeriesNames.value.size === 0
  // Fast path: the backend already compiled a spec and the user hasn't touched
  // the controls — render it verbatim.
  if (vegaLiteSpec.value && intent.value && noOverrides) {
    return vegaLiteSpec.value as Record<string, unknown>
  }
  // Otherwise compile from the effective intent (backend or auto-derived) plus
  // the (coerced) data — this is the path for "Show as graph" on a table and
  // for any toolbar override.
  const overridden: ChartIntent = {
    ...bi,
    kind: (overrideKind.value as ChartIntent['kind']) ?? bi.kind,
    stacked: overrideStacked.value !== null ? overrideStacked.value : (bi.stacked ?? false),
    hideSeries: [...hiddenSeriesNames.value],
  }
  return compileVegaLite(overridden, chartData.value)
})

const isRendered = ref(false)

async function renderChart() {
  if (!containerRef.value) return
  vegaViewRef.value?.finalize()
  vegaViewRef.value = null
  const spec = effectiveSpec.value
  if (!spec || Object.keys(spec).length === 0) return
  try {
    const result = await embed(containerRef.value, spec as Parameters<typeof embed>[1], {
      renderer: 'svg',
      actions: false,
    })
    vegaViewRef.value = result.view
  } catch (err) {
    console.warn('vega-embed render failed', err)
  }
}

onMounted(async () => {
  await renderChart()
  isRendered.value = true
})

onUnmounted(() => {
  vegaViewRef.value?.finalize()
})

async function reRender() {
  isRendered.value = false
  await renderChart()
  isRendered.value = true
}

const onKindChanged = async (kind: string) => {
  overrideKind.value = kind
  await reRender()
}

const onStackedChanged = async (stk: boolean) => {
  overrideStacked.value = stk
  await reRender()
}

const onVisibleSeriesChanged = async () => {
  await reRender()
}

const csvCell = (value: unknown): string => {
  if (value === null || value === undefined) return ''
  const s = typeof value === 'object' ? JSON.stringify(value) : String(value)
  if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`
  return s
}

const onDownloadCsv = () => {
  if (rows.value.length === 0) return
  const xKey = baseIntent.value?.x ?? 'index'
  const cols = [xKey, ...allSeriesNames.value]
  const lines: string[] = [cols.map(csvCell).join(',')]
  rows.value.forEach((row, i) => {
    const xVal = baseIntent.value?.x ? row[baseIntent.value.x] : i
    const cells = [
      csvCell(xVal),
      ...allSeriesNames.value.map((k) => csvCell(row[k])),
    ]
    lines.push(cells.join(','))
  })
  const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'chart.csv'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(a.href)
  toast.add({ severity: 'success', summary: t('table.csvDownloaded'), life: 1500 })
}

const onCopyPng = async () => {
  const view = vegaViewRef.value
  if (!view) return
  try {
    const dataUrl = await view.toImageURL('png')
    const blob = await (await fetch(dataUrl)).blob()
    if (typeof ClipboardItem !== 'undefined' && navigator.clipboard.write) {
      await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })])
      toast.add({ severity: 'success', summary: t('chart.chartCopied'), life: 1500 })
    } else {
      await navigator.clipboard.writeText(dataUrl)
      toast.add({
        severity: 'success',
        summary: t('chart.chartDataUrlCopied'),
        detail: t('chart.imageClipboardUnsupported'),
        life: 2000,
      })
    }
  } catch (err) {
    console.warn('Chart copy failed', err)
    toast.add({ severity: 'warn', summary: t('chat.copyFailed'), life: 2000 })
  }
}

const onShowAsTable = () => {
  emit('display-changed', { viewKind: FormatKind.TABLE })
}

const pointCtxMenu = ref<InstanceType<typeof ContextMenu> | null>(null)

const pointDrilldowns = computed(() =>
  (props.drilldowns ?? []).filter((d) => d.scope === 'point'),
)

const pointMenuItems = computed(() =>
  pointDrilldowns.value.length === 0
    ? [{ label: t('chart.noDrilldowns'), icon: 'pi pi-info-circle', disabled: true }]
    : pointDrilldowns.value.map((dd) => ({
        label: dd.display,
        icon: 'pi pi-chevron-right',
        command: () => {
          toast.add({
            severity: 'info',
            summary: t('table.v21Toast.drilldownSummary'),
            detail: dd.display,
            life: 2500,
          })
        },
      })),
)

const onChartContext = (event: MouseEvent) => {
  event.preventDefault()
  pointCtxMenu.value?.show(event)
}
</script>

<template>
  <div class="chart-renderer">
    <p v-if="text" class="chart-intro">{{ text }}</p>

    <div class="chart-toolbar">
      <SelectButton
        v-model="overrideKind"
        :options="typeOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        :aria-label="t('chart.typeLine') + ' / ' + t('chart.typeBar') + ' / ' + t('chart.typePie')"
        class="type-picker"
        @change="(e: { value: string }) => onKindChanged(e.value)"
      >
        <template #option="{ option }">
          <i :class="option.icon" />
          <span class="ml-1">{{ option.label }}</span>
        </template>
      </SelectButton>

      <MultiSelect
        v-if="allSeriesNames.length > 1"
        v-model="visibleSeriesNames"
        :options="seriesOptions"
        option-label="label"
        option-value="value"
        :placeholder="t('chart.series')"
        :max-selected-labels="2"
        size="small"
        class="series-picker"
        @change="() => onVisibleSeriesChanged()"
      />

      <ToggleButton
        v-if="allSeriesNames.length > 1 && chartKind !== 'pie'"
        :model-value="stacked"
        :on-label="t('chart.stacked')"
        :off-label="t('chart.stack')"
        on-icon="pi pi-th-large"
        off-icon="pi pi-bars"
        size="small"
        class="stack-toggle"
        @update:model-value="(v: boolean) => onStackedChanged(v)"
      />

      <span class="grow" />

      <Button
        text
        size="small"
        icon="pi pi-table"
        :aria-label="t('chart.showAsTable')"
        v-tooltip.bottom="t('chart.showAsTable')"
        @click="onShowAsTable"
      />
      <Button
        text
        size="small"
        icon="pi pi-image"
        :aria-label="t('chart.copyPng')"
        v-tooltip.bottom="t('chart.copyPng')"
        :disabled="rows.length === 0"
        @click="onCopyPng"
      />
      <Button
        text
        size="small"
        icon="pi pi-download"
        :aria-label="t('chart.downloadCsv')"
        v-tooltip.bottom="t('chart.downloadCsv')"
        :disabled="rows.length === 0"
        @click="onDownloadCsv"
      />
    </div>

    <div
      v-if="rows.length === 0"
      class="chart-empty"
      style="color: var(--p-surface-500);"
    >
      {{ t('chart.noData') }}
    </div>
    <div v-else class="chart-host" @contextmenu="onChartContext">
      <div ref="containerRef" class="vega-container" />
    </div>

    <ContextMenu ref="pointCtxMenu" :model="pointMenuItems" />
  </div>
</template>

<style scoped>
.chart-renderer {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  font-size: 0.85rem;
  min-width: 0;
}
.chart-intro {
  margin: 0 0 0.25rem 0;
  color: inherit;
}
.chart-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.4rem;
}
.chart-toolbar .grow { flex: 1; }
.chart-toolbar :deep(.p-button) {
  width: 1.85rem;
  height: 1.85rem;
}
.type-picker :deep(.p-button) {
  width: auto;
  height: auto;
  padding: 0.3rem 0.65rem;
  font-size: 0.78rem;
}
.series-picker {
  font-size: 0.78rem;
  min-width: 9rem;
}
.stack-toggle :deep(.p-togglebutton) {
  font-size: 0.78rem;
  padding: 0.3rem 0.65rem;
}

.chart-host {
  width: 100%;
  height: 320px;
  min-height: 260px;
}
.vega-container {
  width: 100%;
  height: 100%;
}
.chart-empty {
  padding: 1.5rem 0.5rem;
  text-align: center;
}
</style>
