<script setup lang="ts">
// Phase 5: PrimeVue DataTable wrapper.
//
// Two content shapes:
//   - Object               → 2-column key/value table (Property, Value).
//   - Array of objects     → multi-column row table (one row per object).
//
// Client-side actions wired now (FE-5.3 / 5.4):
//   - Copy to clipboard (TSV)
//   - Download CSV
//   - Hide column / Show all columns (right-click on column header)
//   - Drag-reorder columns (DataTable's `reorderableColumns`)
//
// Stubbed-with-toast (FE-5.5 / 5.6) — UI is visible, but server-side action
// lands in v2.1:
//   - Sort
//   - Filter (per-column menu)
//   - Paginate
//   - Row drill-down menu (right-click on a row → drilldowns from envelope)
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import ContextMenu from 'primevue/contextmenu'
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import Popover from 'primevue/popover'
import { useToast } from 'primevue/usetoast'
import { columnStats, isNumericColumn } from './chartAutoIntent'
import { config } from '@/config'
import { FormatKind } from '@/types/envelope'
import type {
  Drilldown,
  TableColumnSpec,
  TableDetails,
  TableHeader,
} from '@/types/envelope'

// envelope/v1 TableColumnSpec dropped the v2 NumberFormatSpec — keep the FE's
// locale-aware number capability as a FE-local optional (the wire won't populate
// it until v1 regains the field; inline-constructed specs in tests still work).
interface FeNumberFormatSpec {
  style?: 'decimal' | 'currency' | 'percent'
  currency?: string
  minimumFractionDigits?: number
  maximumFractionDigits?: number
  useGrouping?: boolean
}

interface Props {
  text?: string
  content?: unknown
  details?: TableDetails
  // Phase 5 reads `drilldowns` directly off props because envelope is
  // shaped at the bubble level — see ChatBubble.vue's `<component :is>` call.
  drilldowns?: Drilldown[]
  // Phase 3: when set (2–4 rows), table rows are clickable.
  pendingSelection?: { count: number; entity_type: string }
  // Phase 3: messageId of the bubble owning this table — passed back in
  // the select-row event so ChatBubble can forward it to dispatchTypedAction.
  messageId?: string
  // Total rows in the *full* server-side result (envelope.current_view.totalRows).
  // The FE only holds a capped sample (`content` = executor `sample_rows`, ≤25),
  // so this can exceed `meta.rows.length`. Feeds the paginator's total and lets
  // the summary popover flag when its stats cover only the loaded sample.
  resultTotalRows?: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  // Phase 6: Show-as-Chart. ChatBubble / PromotedPanel listens and flips
  // the bubble/panel `displayState.viewKind`.
  (e: 'display-changed', state: { viewKind: FormatKind }): void
  // Phase 3: row clicked — ChatBubble forwards to dispatchTypedAction.
  (e: 'select-row', payload: { rowNumber: number; originalMessageId: string }): void
  // Row-detail: "Show detail" on the row context menu. `rowIndex` is the row's
  // stable position in the held rows (== server `sample_rows` index); ChatBubble
  // pairs it with the envelope's bubble_id to arm a TurnSelection.
  (e: 'show-detail', payload: { rowIndex: number }): void
  // Stage 3.2 typed actions — reshape/refetch the bubble's cached rows via
  // POST /v1/action (ChatBubble supplies the bubble_id). Each emits a replacing
  // envelope (sort/filter/paginate) or a new drilldown bubble (drilldown).
  (e: 'sort', payload: { column: string; direction: 'asc' | 'desc' }): void
  (e: 'filter', payload: { column: string; operator: string; value: unknown }): void
  (e: 'paginate', payload: { page: number; pageSize: number }): void
  (e: 'drilldown', payload: { rowIndex: number }): void
}>()
const toast = useToast()
const { t, locale } = useI18n()

// Phase 3: selectable mode state
const selectedRowIndex = ref<number | null>(null)

const isSelectable = computed(() => {
  if (!props.pendingSelection) return false
  return 2 <= props.pendingSelection.count && props.pendingSelection.count <= 4
})

const onRowSelect = (rowIndex: number) => {
  if (!isSelectable.value) return
  selectedRowIndex.value = rowIndex
  emit('select-row', {
    rowNumber: rowIndex + 1, // 1-based
    originalMessageId: props.messageId ?? '',
  })
}

const isRowSelected = (rowIndex: number) => selectedRowIndex.value === rowIndex

// -------- shape detection -------------------------------------------------

interface RowMeta {
  rows: Record<string, unknown>[]
  isKeyValue: boolean
}

const meta = computed<RowMeta>(() => {
  const c = props.content
  if (Array.isArray(c)) {
    return {
      rows: (c as unknown[]).filter((r) => r && typeof r === 'object') as Record<
        string,
        unknown
      >[],
      isKeyValue: false,
    }
  }
  if (c && typeof c === 'object') {
    const entries = Object.entries(c as Record<string, unknown>).map(([k, v]) => ({
      property: k,
      value: v,
    }))
    return { rows: entries, isKeyValue: true }
  }
  return { rows: [], isKeyValue: false }
})

// The DataTable needs a stable `dataKey` for selection identity, but the source
// rows carry no id. Bind a thin copy that injects `__rowIndex__` (the row's
// stable position in the held sample) and resolve the dataKey against it. We
// keep `meta.rows` clean so column derivation, stats, and CSV/TSV export never
// see the synthetic key.
const ROW_INDEX_KEY = '__rowIndex__'
const tableRows = computed<Record<string, unknown>[]>(() =>
  meta.value.rows.map((row, i) => ({ ...row, [ROW_INDEX_KEY]: i })),
)

// -------- column derivation ----------------------------------------------

interface ResolvedColumn {
  name: string
  title: string
  spec?: TableColumnSpec
  visible: boolean
}

// Order: explicit `details.headers` wins; otherwise use the union of keys
// across rows in first-appearance order. For the key/value shape the
// columns are fixed: Property + Value.
const baseColumns = computed<ResolvedColumn[]>(() => {
  if (meta.value.isKeyValue) {
    return [
      { name: 'property', title: 'Property', visible: true },
      { name: 'value', title: 'Value', visible: true },
    ]
  }

  const headers = props.details?.headers
  const columnSpecs = props.details?.columns ?? {}

  if (headers && headers.length > 0) {
    return headers.map((h: TableHeader) => ({
      name: h.name,
      title: h.title || h.name,
      spec: columnSpecs[h.name],
      visible: !columnSpecs[h.name]?.hidden,
    }))
  }

  // Fallback: derive from row keys.
  const seen: string[] = []
  const seenSet = new Set<string>()
  for (const row of meta.value.rows) {
    for (const key of Object.keys(row)) {
      if (!seenSet.has(key)) {
        seenSet.add(key)
        seen.push(key)
      }
    }
  }
  return seen.map((k) => ({
    name: k,
    title: k,
    spec: columnSpecs[k],
    visible: !columnSpecs[k]?.hidden,
  }))
})

// Track visible columns separately so the user's hide/show survives
// re-renders. Reset whenever the upstream `details` change (a fresh
// envelope replaces the prior one).
const hiddenColumns = ref<Set<string>>(new Set())

watch(
  () => baseColumns.value.map((c) => c.name).join('|'),
  () => {
    hiddenColumns.value = new Set(
      baseColumns.value.filter((c) => !c.visible).map((c) => c.name),
    )
  },
  { immediate: true },
)

const displayColumns = computed<ResolvedColumn[]>(() =>
  baseColumns.value.map((c) => ({
    ...c,
    visible: !hiddenColumns.value.has(c.name),
  })),
)

const visibleColumns = computed(() => displayColumns.value.filter((c) => c.visible))

const allColumnsVisible = computed(() => hiddenColumns.value.size === 0)

// -------- formatting -----------------------------------------------------

// Minimal C/Java-style format string interpreter — enough for the common
// cases the agent asks for (`%.2f`, `%d`, `%05d`, `%.0f%%`, `%s`). Anything
// else falls through to String(value).
const FORMAT_TOKEN = /%(?:(?<flags>[-+ 0#]*)(?<width>\d+)?(?:\.(?<prec>\d+))?)?(?<type>[dfeEgGsx])/g

const applyFormat = (raw: string, value: unknown): string => {
  if (value === null || value === undefined) return ''
  return raw.replace(FORMAT_TOKEN, (...args) => {
    const groups = args[args.length - 1] as {
      flags?: string
      width?: string
      prec?: string
      type: string
    }
    const flags = groups.flags ?? ''
    const width = groups.width ? parseInt(groups.width, 10) : 0
    const prec = groups.prec !== undefined ? parseInt(groups.prec, 10) : -1
    const type = groups.type

    let out = ''
    const num = typeof value === 'number' ? value : Number(value)
    switch (type) {
      case 'd':
        if (Number.isFinite(num)) {
          const sign = num < 0 ? '-' : flags.includes('+') ? '+' : flags.includes(' ') ? ' ' : ''
          out = sign + Math.trunc(Math.abs(num)).toString()
        } else {
          out = String(value)
        }
        break
      case 'f':
        if (Number.isFinite(num)) {
          out = (prec >= 0 ? num.toFixed(prec) : num.toString())
        } else {
          out = String(value)
        }
        break
      case 'e':
      case 'E':
        if (Number.isFinite(num)) {
          out = num.toExponential(prec >= 0 ? prec : 6)
          if (type === 'E') out = out.toUpperCase()
        } else {
          out = String(value)
        }
        break
      case 'g':
      case 'G':
        if (Number.isFinite(num)) {
          out = prec >= 0 ? num.toPrecision(prec) : num.toString()
          if (type === 'G') out = out.toUpperCase()
        } else {
          out = String(value)
        }
        break
      case 'x':
        if (Number.isFinite(num)) out = Math.trunc(num).toString(16)
        else out = String(value)
        break
      case 's':
      default:
        out = String(value)
        break
    }
    if (width > out.length) {
      const pad = flags.includes('0') && /[def]/.test(type) ? '0' : ' '
      if (flags.includes('-')) out = out.padEnd(width, pad)
      else out = out.padStart(width, pad)
    }
    return out
  })
}

// Grouped, 2-decimal default for numeric output that carries no explicit
// `number` descriptor (e.g. the Σ sum of a raw integer column).
const DEFAULT_NUMBER_OPTIONS: Intl.NumberFormatOptions = {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  useGrouping: true,
}

// Translate a column's locale-agnostic `number` intent into Intl options.
// Returns null when the column declares no numeric formatting.
const numberFormatOptions = (column: ResolvedColumn): Intl.NumberFormatOptions | null => {
  const n = (column.spec as (TableColumnSpec & { number?: FeNumberFormatSpec }) | undefined)?.number
  if (!n) return null
  const o: Intl.NumberFormatOptions = {}
  if (n.style) o.style = n.style
  if (n.currency) o.currency = n.currency
  if (n.minimumFractionDigits != null) o.minimumFractionDigits = n.minimumFractionDigits
  if (n.maximumFractionDigits != null) o.maximumFractionDigits = n.maximumFractionDigits
  if (n.useGrouping != null) o.useGrouping = n.useGrouping
  return o
}

// Render a number with the active UI locale (vue-i18n). The locale stays on the
// client — the backend only ships the format intent.
const localizedNumber = (num: number, options: Intl.NumberFormatOptions): string => {
  try {
    return new Intl.NumberFormat(locale.value, options).format(num)
  } catch {
    return String(num)
  }
}

// formatCell renders one value for display. With `invariant` (CSV/TSV export) we
// drop grouping, the currency unit and the locale and emit a machine-parseable
// decimal — a `cs` "1 234,56" with a comma column-separator would corrupt the
// file. This matches the pre-existing export behaviour (printf "%.2f").
const formatCell = (
  column: ResolvedColumn,
  value: unknown,
  opts: { invariant?: boolean } = {},
): string => {
  const numOpts = numberFormatOptions(column)
  if (numOpts && value !== null && value !== undefined) {
    const num = typeof value === 'number' ? value : Number(value)
    if (Number.isFinite(num)) {
      if (opts.invariant) {
        return new Intl.NumberFormat('en-US', {
          minimumFractionDigits: numOpts.minimumFractionDigits,
          maximumFractionDigits: numOpts.maximumFractionDigits,
          useGrouping: false,
        }).format(num)
      }
      return localizedNumber(num, numOpts)
    }
    // Non-numeric value under a number spec — fall through to coercion below.
  }
  // Deprecated printf fallback for envelopes from older backends.
  if (column.spec?.format) {
    try {
      return applyFormat(column.spec.format, value)
    } catch {
      // formatter blew up — fall through to string coercion
    }
  }
  if (value === null || value === undefined) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

const cellAlignClass = (column: ResolvedColumn): string => {
  switch (column.spec?.alignment) {
    case 'center':
      return 'text-center'
    case 'right':
      return 'text-right'
    default:
      return ''
  }
}

const cellWidthStyle = (column: ResolvedColumn): Record<string, string> => {
  if (column.spec?.width) return { width: `${column.spec.width}px` }
  return {}
}

// -------- TableDetails → DataTable props ---------------------------------

const stripedRows = computed(() => {
  const mode = props.details?.alternateColors
  return mode === 'Rows' || mode === 'Both'
})

// Mode `'Cols'` / `'Both'` adds vertical zebra-stripes via :deep CSS.
const colStripeClass = computed(() => {
  const mode = props.details?.alternateColors
  return mode === 'Cols' || mode === 'Both' ? 'table-striped-cols' : ''
})

// -------- column hide / show all -----------------------------------------

const hideColumn = (column: ResolvedColumn) => {
  hiddenColumns.value = new Set([...hiddenColumns.value, column.name])
}

const showAllColumns = () => {
  hiddenColumns.value = new Set()
}

// -------- copy / download ------------------------------------------------

const tsvCell = (value: unknown): string => {
  if (value === null || value === undefined) return ''
  const s = typeof value === 'object' ? JSON.stringify(value) : String(value)
  return s.replace(/\t/g, ' ').replace(/\r?\n/g, ' ')
}

const csvCell = (value: unknown): string => {
  if (value === null || value === undefined) return ''
  const s = typeof value === 'object' ? JSON.stringify(value) : String(value)
  if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`
  return s
}

const buildSeparated = (cellFn: (v: unknown) => string, sep: string): string => {
  const cols = visibleColumns.value
  const headerLine = cols.map((c) => cellFn(c.title)).join(sep)
  const rowLines = meta.value.rows.map((row) =>
    cols.map((c) => cellFn(formatCell(c, row[c.name], { invariant: true }))).join(sep),
  )
  return [headerLine, ...rowLines].join('\n')
}

const onCopyTsv = async () => {
  if (visibleColumns.value.length === 0) return
  const tsv = buildSeparated(tsvCell, '\t')
  try {
    await navigator.clipboard.writeText(tsv)
    toast.add({ severity: 'success', summary: t('table.tableCopied'), life: 1500 })
  } catch (err) {
    console.warn('Clipboard write failed', err)
    toast.add({ severity: 'warn', summary: t('chat.copyFailed'), life: 2000 })
  }
}

const onDownloadCsv = () => {
  if (visibleColumns.value.length === 0) return
  const csv = buildSeparated(csvCell, ',')
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'table.csv'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(a.href)
  toast.add({ severity: 'success', summary: t('table.csvDownloaded'), life: 1500 })
}

// -------- column header context menu (Hide / Show all) -------------------

const headerCtxMenu = ref<InstanceType<typeof Menu> | null>(null)
const headerCtxColumn = ref<ResolvedColumn | null>(null)

const headerMenuItems = computed(() => [
  {
    label: t('table.hideColumn'),
    icon: 'pi pi-eye-slash',
    disabled: !headerCtxColumn.value,
    command: () => {
      if (headerCtxColumn.value) hideColumn(headerCtxColumn.value)
    },
  },
  {
    label: t('table.showAllColumns'),
    icon: 'pi pi-eye',
    disabled: allColumnsVisible.value,
    command: () => showAllColumns(),
  },
])

const onHeaderContext = (event: MouseEvent, column: ResolvedColumn) => {
  event.preventDefault()
  headerCtxColumn.value = column
  headerCtxMenu.value?.show(event)
}

// -------- row context menu (drill-down — Phase v2.1 stub) ----------------

const rowCtxMenu = ref<InstanceType<typeof ContextMenu> | null>(null)
const rowCtxRow = ref<Record<string, unknown> | null>(null)
// Stable index of the right-clicked row within the held rows. Equals the
// server `sample_rows` index (content is rendered in sample_rows order), so it
// survives the FE's sort/filter/paging — unlike a PrimeVue display index.
const rowCtxIndex = ref<number>(-1)

const rowDrilldowns = computed(() =>
  (props.drilldowns ?? []).filter((d) => d.scope === 'row'),
)

const rowMenuItems = computed(() => {
  // "Show detail" is always offered — it arms a selection reference resolved
  // server-side. Any row drilldowns (still stubbed) follow it.
  const items: Array<Record<string, unknown>> = [
    {
      label: t('detail.menuItem'),
      icon: 'pi pi-search-plus',
      command: () => {
        if (rowCtxIndex.value >= 0) emit('show-detail', { rowIndex: rowCtxIndex.value })
      },
    },
  ]
  for (const dd of rowDrilldowns.value) {
    items.push({
      label: dd.display,
      icon: 'pi pi-chevron-right',
      command: () => {
        // Stage 3.2 T2/T5: drill into the right-clicked row via the select_row
        // typed action (opens a new bubble). The BFF resolves the row through
        // the bubble's Drilldown (target_pattern_id + arg_mapping).
        if (rowCtxIndex.value >= 0) emit('drilldown', { rowIndex: rowCtxIndex.value })
      },
    })
  }
  return items
})

const onRowContext = (event: MouseEvent, row: Record<string, unknown>) => {
  if (meta.value.isKeyValue) return
  rowCtxRow.value = row
  // The bound rows carry the synthetic `__rowIndex__` (their stable position in
  // the held sample); prefer it over an identity scan of the clean rows.
  const idx = row[ROW_INDEX_KEY]
  rowCtxIndex.value = typeof idx === 'number' ? idx : meta.value.rows.indexOf(row)
  rowCtxMenu.value?.show(event)
}

// -------- Stage 3.2 typed actions: sort / filter / paginate --------------
//
// DataTable's sort/filter/page events drive the data-shaping typed actions
// (POST /v1/action). ChatBubble pairs each with the bubble_id and the BFF
// reshapes the cached rows, streaming back a replacing envelope.

const onSort = (e: { sortField?: unknown; sortOrder?: number | null }) => {
  const column = typeof e.sortField === 'string' ? e.sortField : ''
  if (!column) return
  emit('sort', { column, direction: e.sortOrder === -1 ? 'desc' : 'asc' })
}

const onFilter = (e: { filters?: Record<string, unknown> }) => {
  // PrimeVue hands us the full filter model (every filterable column), so a
  // cleared column shows up with an empty value. The BFF filter directive is
  // one column+operator+value per emit and replaces a column's prior filter; so
  // emit each *active* filter, and for a *cleared* column emit a no-op predicate
  // (`contains ""` matches every row) so the server-side filter is dropped and
  // the table isn't left stuck filtered.
  for (const [column, raw] of Object.entries(e.filters ?? {})) {
    const f = raw as { value?: unknown; matchMode?: string } | undefined
    if (!f || typeof f !== 'object') continue
    const active = f.value != null && f.value !== ''
    emit('filter', {
      column,
      operator: active ? (f.matchMode === 'equals' ? 'eq' : 'contains') : 'contains',
      value: active ? f.value : '',
    })
  }
}

const onPage = (e: { page?: number; rows?: number }) => {
  emit('paginate', { page: (e.page ?? 0) + 1, pageSize: e.rows ?? rowsPerPage.value })
}

// -------- paginator config -----------------------------------------------

const paging = computed(() => props.details?.paging)
// Prefer an explicit paging total, then the envelope's result total, then the
// loaded sample size as a last resort.
const totalRows = computed(
  () => paging.value?.totalRows ?? props.resultTotalRows ?? meta.value.rows.length,
)
// Page size: an explicit server `paging.pageSize` wins; otherwise the runtime
// config knob (VITE_TABLE_PAGE_SIZE, default 25).
const rowsPerPage = computed(() => paging.value?.pageSize ?? config.table.pageSize)

const showPaginator = computed(() => {
  if (meta.value.isKeyValue) return false
  if (paging.value) return true
  // Auto-paginate large client-side tables for ergonomics.
  return meta.value.rows.length > rowsPerPage.value
})

// -------- Show as Chart (FE-6.6) -----------------------------------------

const isNumericLike = (value: unknown): boolean => {
  if (typeof value === 'number') return Number.isFinite(value)
  if (typeof value === 'string' && value.trim() !== '') return Number.isFinite(Number(value))
  return false
}

// Show-as-Chart only makes sense for arrays of objects with at least one
// numeric column to plot — anything else (key/value tables, all-string
// rows) the user should keep reading as a table.
const canShowAsChart = computed(() => {
  if (meta.value.isKeyValue) return false
  const sample = meta.value.rows[0]
  if (!sample) return false
  return Object.values(sample).some((v) => isNumericLike(v))
})

const onShowAsChart = () => {
  emit('display-changed', { viewKind: FormatKind.CHART })
}

// -------- Σ column summary popover ---------------------------------------

const sumPopover = ref<InstanceType<typeof Popover> | null>(null)
// All aggregate values are pre-formatted strings (locale-aware via the column's
// `number` intent); `rows` is the sample size we computed over and `empty` the
// blank-cell count. `total` is the full result size when it exceeds `rows`.
const activeSum = ref<{
  title: string
  value: string
  min: string
  max: string
  avg: string
  empty: number
  count: number
  total: number | null
} | null>(null)

// Numeric column names, computed once over the held rows. Skips the synthetic
// row-number column and key/value tables (where summing makes no sense).
const numericColumns = computed<Set<string>>(() => {
  const s = new Set<string>()
  if (meta.value.isKeyValue) return s
  for (const col of visibleColumns.value) {
    if (col.name === '#') continue
    if (isNumericColumn(meta.value.rows, col.name)) s.add(col.name)
  }
  return s
})

// The sum follows the column's own `number` intent so the total reads in the
// same units/decimals as the cells (a CZK column sums to CZK). Columns with no
// descriptor (e.g. a raw integer column) fall back to a grouped, 2-decimal
// default. Locale is the active UI language, so separators match what the user
// sees — `cs` → "1 234 567,89", `en` → "1,234,567.89".
const onSumClick = (event: MouseEvent, col: ResolvedColumn) => {
  const stats = columnStats(meta.value.rows, col.name)
  const options = numberFormatOptions(col) ?? DEFAULT_NUMBER_OPTIONS
  const fmt = (n: number | null) => (n === null ? '—' : localizedNumber(n, options))
  // The summary covers the loaded sample (meta.rows); flag the full result size
  // when the server matched more rows than the FE holds (`totalRows` resolves the
  // best-known total — see the paginator computed).
  const total = totalRows.value > stats.rows ? totalRows.value : null
  activeSum.value = {
    title: col.title,
    value: fmt(stats.sum),
    min: fmt(stats.min),
    max: fmt(stats.max),
    avg: fmt(stats.avg),
    empty: stats.empty,
    count: stats.rows,
    total,
  }
  sumPopover.value?.show(event, event.currentTarget as HTMLElement)
}
</script>

<template>
  <div class="table-renderer">
    <p v-if="text" class="table-intro">{{ text }}</p>

    <div class="table-toolbar">
      <Button
        v-if="canShowAsChart"
        text
        size="small"
        icon="pi pi-chart-bar"
        :aria-label="t('table.showAsChart')"
        v-tooltip.bottom="t('table.showAsChart')"
        @click="onShowAsChart"
      />
      <Button
        text
        size="small"
        icon="pi pi-copy"
        :aria-label="t('table.copyTsv')"
        v-tooltip.bottom="t('table.copyTsv')"
        :disabled="meta.rows.length === 0"
        @click="onCopyTsv"
      />
      <Button
        text
        size="small"
        icon="pi pi-download"
        :aria-label="t('table.downloadCsv')"
        v-tooltip.bottom="t('table.downloadCsv')"
        :disabled="meta.rows.length === 0"
        @click="onDownloadCsv"
      />
      <Button
        v-if="!allColumnsVisible"
        text
        size="small"
        icon="pi pi-eye"
        :aria-label="t('table.showAllColumns')"
        v-tooltip.bottom="t('table.showAllColumns')"
        @click="showAllColumns"
      />
    </div>

    <p v-if="isSelectable" class="select-row-caption">
      {{ t('chat.selectRowPrompt', { count: pendingSelection?.count }) }}
    </p>

    <DataTable
      :value="tableRows"
      :striped-rows="stripedRows"
      :class="['phase5-table', colStripeClass]"
      :reorderable-columns="true"
      :paginator="showPaginator"
      :rows="rowsPerPage"
      :total-records="totalRows"
      :lazy="!!paging"
      filter-display="menu"
      removable-sort
      :selection-mode="isSelectable ? 'single' : undefined"
      data-key="__rowIndex__"
      size="small"
      @sort="onSort"
      @filter="onFilter"
      @page="onPage"
      @row-contextmenu="(e) => onRowContext(e.originalEvent as MouseEvent, e.data)"
      @row-click="(e) => { if (isSelectable) onRowSelect(e.index) }"
    >
      <template #empty>
        <div class="table-empty">{{ t('table.empty') }}</div>
      </template>

      <Column
        v-for="col in visibleColumns"
        :key="col.name"
        :field="col.name"
        :sortable="!meta.isKeyValue"
        :show-filter-menu="!meta.isKeyValue"
        :filter-match-mode-options="[
          { label: 'Contains', value: 'contains' },
          { label: 'Equals', value: 'equals' },
        ]"
        :style="cellWidthStyle(col)"
      >
        <template #header>
          <span
            class="col-header"
            :class="cellAlignClass(col)"
            @contextmenu="(e: MouseEvent) => onHeaderContext(e, col)"
          >
            {{ col.title }}
          </span>
          <button
            v-if="numericColumns.has(col.name)"
            type="button"
            class="col-sum-btn"
            :aria-label="t('table.sumAria', { col: col.title })"
            v-tooltip.bottom="t('table.sumTooltip')"
            @click.stop="onSumClick($event, col)"
          >Σ</button>
        </template>
        <template #body="{ data, index }">
          <span
            class="cell-content"
            :class="[
              cellAlignClass(col),
              col.name === '#' ? 'row-number-cell' : '',
              isRowSelected(index) && isSelectable ? 'row-selected' : '',
            ]"
          >{{ formatCell(col, data[col.name]) }}</span>
        </template>
        <template #filter="{ filterModel, filterCallback }">
          <input
            v-model="filterModel.value"
            type="text"
            class="col-filter-input"
            :placeholder="t('table.filterPlaceholder')"
            @keydown.enter="filterCallback()"
          />
        </template>
      </Column>
    </DataTable>

    <Menu ref="headerCtxMenu" :model="headerMenuItems" popup />
    <ContextMenu ref="rowCtxMenu" :model="rowMenuItems" />

    <Popover ref="sumPopover">
      <div v-if="activeSum" class="col-sum-popover">
        <div class="col-sum-title">{{ activeSum.title }}</div>
        <dl class="col-sum-stats">
          <dt>{{ t('table.sumLabel') }}</dt>
          <dd class="col-sum-value">{{ activeSum.value }}</dd>
          <dt>{{ t('table.statMin') }}</dt>
          <dd>{{ activeSum.min }}</dd>
          <dt>{{ t('table.statMax') }}</dt>
          <dd>{{ activeSum.max }}</dd>
          <dt>{{ t('table.statAvg') }}</dt>
          <dd>{{ activeSum.avg }}</dd>
          <dt>{{ t('table.statEmpty') }}</dt>
          <dd>{{ activeSum.empty }}</dd>
        </dl>
        <div class="col-sum-caption">
          {{
            activeSum.total != null
              ? t('table.sumCaptionPartial', { n: activeSum.count, total: activeSum.total })
              : t('table.sumCaption', { n: activeSum.count })
          }}
        </div>
      </div>
    </Popover>
  </div>
</template>

<style scoped>
.table-renderer {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  font-size: 0.85rem;
}
.table-intro {
  margin: 0 0 0.25rem 0;
  color: inherit;
}
.table-toolbar {
  display: flex;
  gap: 0.25rem;
  margin-left: -0.25rem;
}
.table-toolbar :deep(.p-button) {
  width: 1.75rem;
  height: 1.75rem;
}
.col-header {
  display: inline-block;
  width: 100%;
  font-weight: 600;
  color: var(--p-surface-800);
}
/* Body cells: the span must fill the cell for text-align to take effect — a
   bare inline span shrinks to its content and stays left regardless of
   text-align (the header span already does this via .col-header). */
.cell-content {
  display: inline-block;
  width: 100%;
}
.text-center { text-align: center; }
.text-right  { text-align: right; }
.col-filter-input {
  width: 100%;
  padding: 0.35rem 0.5rem;
  font-size: 0.8rem;
  border: 1px solid var(--p-surface-300);
  border-radius: 0.4rem;
  background-color: #fff;
  color: var(--p-surface-900);
}

.phase5-table {
  font-size: 0.78rem;
}
.phase5-table :deep(.p-datatable-thead > tr > th) {
  background-color: var(--p-surface-100);
  border-bottom: 1px solid var(--p-surface-300);
  padding: 0.45rem 0.65rem;
}
.phase5-table :deep(.p-datatable-tbody > tr > td) {
  padding: 0.4rem 0.65rem;
  border-bottom: 1px solid var(--p-surface-100);
}
.phase5-table.table-striped-cols :deep(.p-datatable-tbody > tr > td:nth-child(even)),
.phase5-table.table-striped-cols :deep(.p-datatable-thead > tr > th:nth-child(even)) {
  background-color: color-mix(in srgb, var(--p-surface-100) 60%, transparent);
}
.table-empty {
  padding: 1rem;
  text-align: center;
  color: var(--p-surface-500);
}
.row-number-cell {
  font-family: monospace;
  font-weight: 600;
  color: var(--p-primary-color);
}
.row-selected {
  background-color: var(--p-primary-100) !important;
}
.select-row-caption {
  margin: 0;
  font-size: 0.8rem;
  color: var(--p-surface-600);
  font-style: italic;
}

/* Σ sum affordance — sits inline in the header next to the title. */
.col-sum-btn {
  margin-left: 0.25rem;
  padding: 0 0.2rem;
  border: none;
  background: transparent;
  color: inherit;
  font-size: 0.9em;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  opacity: 0.5;
  transition: opacity 0.15s ease;
}
.col-sum-btn:hover {
  opacity: 1;
}
.col-sum-popover {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  padding: 0.1rem 0.25rem;
  font-size: 0.82rem;
}
.col-sum-title {
  font-weight: 600;
  color: var(--p-surface-800);
}
/* Two-column label/value grid: labels left, right-aligned tabular numerics. */
.col-sum-stats {
  display: grid;
  grid-template-columns: auto auto;
  gap: 0.1rem 0.75rem;
  margin: 0.15rem 0;
}
.col-sum-stats dt {
  color: var(--p-surface-600);
}
.col-sum-stats dd {
  margin: 0;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.col-sum-value {
  font-weight: 600;
}
.col-sum-caption {
  font-size: 0.74rem;
  color: var(--p-surface-500);
}
</style>
