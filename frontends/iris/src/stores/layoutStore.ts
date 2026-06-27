// Imperative dockview controls + UI flags that don't belong inside dockview's
// own state. Per architecture.md §2.3.
//
// dockview owns: which groups exist, which panels are in them, order, sizes.
// layoutStore owns: collapse-toggles for the Tabs and Graph groups, plus other
// UI flags that span panels (e.g. "scroll-to-latest" for chat in Phase 8).
//
// Group collapse implementation note:
//   dockview-vue 5.2 exposes `group.api.setSize({ width, height })`. To
//   "collapse" a group we record its current width/height, then setSize to ~0;
//   to "expand" we restore the recorded value. The group's resize handle
//   becomes the thin re-expand affordance, so we don't need a separate handle
//   component.
import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import type { DockviewApi, DockviewGroupPanel } from 'dockview-core'
import type { DisplayState, FormatEnvelope } from '@/types/envelope'
import { useTabsStore } from '@/stores/tabsStore'
import {
  BOTTOM_PANES,
  defaultPaneVisibility,
  type BottomPaneId,
} from '@/components/layout/bottomPaneRegistry'

const COLLAPSED_THRESHOLD_PX = 32

// v2.1 layout defaults (used on first load and on Reset layout).
const DEFAULT_BOTTOM_ROW_SIZE_PX = 260
const DEFAULT_TOP_ROW_SPLIT_PCT = 60 // chat = 60%, tabs = 40%
const SPLIT_PCT_MIN = 15
const SPLIT_PCT_MAX = 85

const FALLBACK_TAB_TITLE = 'Untitled'

/** Phase 4: pick a sensible tab title for an envelope. Order:
 *   1. The first markdown heading in the text (`# …` / `## …`).
 *   2. The first non-empty line, truncated.
 *   3. "Untitled".
 *
 * (v1's `MarkdownDetails.open_in_tab_default_title` hint was dropped in the
 * Stage 07 atomic cutover.)
 */
const deriveTabTitle = (envelope: FormatEnvelope): string => {
  const text = envelope.text ?? ''
  const heading = text.match(/^#{1,6}\s+(.+?)\s*#*\s*$/m)
  if (heading?.[1]) return heading[1].trim().slice(0, 60)

  const firstLine = text
    .split('\n')
    .map((l) => l.trim())
    .find((l) => l.length > 0)
  if (firstLine) return firstLine.slice(0, 60)

  return FALLBACK_TAB_TITLE
}

export const useLayoutStore = defineStore('layout', () => {
  // shallowRef — DockviewApi is a non-reactive instance; reactivity tracking
  // would just churn for no benefit.
  const dockviewApi = shallowRef<DockviewApi | null>(null)

  // Tracked so the Tabs group can be re-resolved across panel additions and,
  // when it gets removed because its last panel closed, re-created at the
  // canonical position (right of chat-panel).
  const tabsGroupId = ref<string | null>(null)

  // Restore values captured at the moment of collapse so re-expand returns
  // the user's last preferred size.
  const tabsRestoreWidth = ref<number | null>(null)
  const graphRestoreHeight = ref<number | null>(null)

  const tabsCollapsed = ref(false)
  const graphCollapsed = ref(false)

  // ── v2.1 slot model state ────────────────────────────────────────────
  //
  // Owned here, persisted by `layoutPersistence.ts`, consumed by
  // `DockviewWorkspace.vue` (panes + splitter sizes) and `SideNavigation.vue`
  // (View menu + collapse toggle).
  const sideNavCollapsed = ref(false)
  const paneVisibility = ref<Record<string, boolean>>(defaultPaneVisibility())
  const activePaneId = ref<BottomPaneId | null>('graph')
  const bottomRowSize = ref<number>(DEFAULT_BOTTOM_ROW_SIZE_PX)
  const topRowSplitPct = ref<number>(DEFAULT_TOP_ROW_SPLIT_PCT)

  /** Visible panes in registry order — the source of truth for the bottom
   *  row tab strip. */
  const visiblePanes = computed(() =>
    BOTTOM_PANES.filter((p) => paneVisibility.value[p.id] === true),
  )

  const isPaneVisible = (id: BottomPaneId): boolean =>
    paneVisibility.value[id] === true

  const togglePane = (id: BottomPaneId) => {
    paneVisibility.value = {
      ...paneVisibility.value,
      [id]: !paneVisibility.value[id],
    }
    // Active-pane housekeeping. If we just hid the active pane, fall through
    // to the next visible pane (registry order). If the toggle made a pane
    // visible and there's no active one, activate the new one.
    if (!paneVisibility.value[id] && activePaneId.value === id) {
      const next = BOTTOM_PANES.find((p) => paneVisibility.value[p.id])
      activePaneId.value = next ? next.id : null
    } else if (paneVisibility.value[id] && activePaneId.value == null) {
      activePaneId.value = id
    }
  }

  const setActivePane = (id: BottomPaneId | null) => {
    activePaneId.value = id
  }

  const setBottomRowSize = (px: number) => {
    if (px > COLLAPSED_THRESHOLD_PX) bottomRowSize.value = Math.round(px)
  }

  const setTopRowSplitPct = (pct: number) => {
    topRowSplitPct.value = Math.max(SPLIT_PCT_MIN, Math.min(SPLIT_PCT_MAX, pct))
  }

  const toggleSideNavCollapsed = () => {
    sideNavCollapsed.value = !sideNavCollapsed.value
  }

  const setSideNavCollapsed = (collapsed: boolean) => {
    sideNavCollapsed.value = collapsed
  }

  /** Hard reset to v2.1 defaults — used by the View menu's Reset action and
   *  by the persistence layer when a corrupt blob is discarded. */
  const resetLayout = () => {
    sideNavCollapsed.value = false
    paneVisibility.value = defaultPaneVisibility()
    activePaneId.value = 'graph'
    bottomRowSize.value = DEFAULT_BOTTOM_ROW_SIZE_PX
    topRowSplitPct.value = DEFAULT_TOP_ROW_SPLIT_PCT
  }

  const setApi = (api: DockviewApi | null) => {
    dockviewApi.value = api
    if (!api) tabsGroupId.value = null
  }

  const setTabsGroupId = (id: string | null) => {
    tabsGroupId.value = id
  }

  const getGroup = (panelId: string): DockviewGroupPanel | null => {
    const api = dockviewApi.value
    if (!api) return null
    const panel = api.getPanel(panelId)
    return panel?.group ?? null
  }

  /** Resolve the Tabs group panel — prefers the tracked id, falls back to
   * the placeholder's group, then to "any group right of chat-panel". */
  const resolveTabsGroup = (): DockviewGroupPanel | null => {
    const api = dockviewApi.value
    if (!api) return null
    if (tabsGroupId.value) {
      const grp = api.getGroup(tabsGroupId.value)
      if (grp) return grp as DockviewGroupPanel
    }
    const empty = api.getPanel('tabs-empty')
    if (empty?.group) return empty.group
    return null
  }

  const collapseTabs = () => {
    const group = resolveTabsGroup()
    if (!group) return
    if (group.width > COLLAPSED_THRESHOLD_PX) {
      tabsRestoreWidth.value = group.width
    }
    group.api.setSize({ width: 0 })
    tabsCollapsed.value = true
  }

  const expandTabs = () => {
    const group = resolveTabsGroup()
    if (!group) return
    group.api.setSize({ width: tabsRestoreWidth.value ?? 480 })
    tabsCollapsed.value = false
  }

  const toggleTabsCollapsed = () => {
    if (tabsCollapsed.value) expandTabs()
    else collapseTabs()
  }

  const collapseGraph = () => {
    const group = getGroup('graph-panel')
    if (!group) return
    if (group.height > COLLAPSED_THRESHOLD_PX) {
      graphRestoreHeight.value = group.height
    }
    group.api.setSize({ height: 0 })
    graphCollapsed.value = true
  }

  const expandGraph = () => {
    const group = getGroup('graph-panel')
    if (!group) return
    group.api.setSize({ height: graphRestoreHeight.value ?? 260 })
    graphCollapsed.value = false
  }

  const toggleGraphCollapsed = () => {
    if (graphCollapsed.value) expandGraph()
    else collapseGraph()
  }

  /** Phase 4 — promote a chat-bubble envelope to a new tab in the Tabs group.
   *
   * Returns the dockview panel id (== tabsStore key) on success, `null` if
   * dockview isn't ready yet (shouldn't happen during normal use).
   */
  const openEnvelopeInTab = (
    envelope: FormatEnvelope,
    options: {
      sourceMessageId?: string
      displayState?: DisplayState
      title?: string
    } = {},
  ): string | null => {
    const api = dockviewApi.value
    if (!api) return null

    const tabsStore = useTabsStore()
    const title = options.title ?? deriveTabTitle(envelope)

    const panelId = tabsStore.add({
      title,
      sourceMessageId: options.sourceMessageId,
      format: envelope,
      displayState: options.displayState,
    })

    // Position: prefer the live Tabs group (so the tab joins the existing
    // strip); fall back to recreating it right of chat-panel.
    const tabsGroup = resolveTabsGroup()
    const position = tabsGroup
      ? { referenceGroup: tabsGroup.id, direction: 'within' as const }
      : { referencePanel: 'chat-panel', direction: 'right' as const }

    api.addPanel({
      id: panelId,
      component: 'PromotedPanel',
      tabComponent: 'TabHeader',
      title,
      params: { panelId },
      position,
    })

    // Cache the (possibly new) Tabs group id, ensure its tab strip is
    // visible, and dismiss the placeholder if it's still around.
    const newGroup = api.getPanel(panelId)?.group
    if (newGroup) {
      tabsGroupId.value = newGroup.id
      newGroup.header.hidden = false
    }

    const empty = api.getPanel('tabs-empty')
    if (empty) empty.api.close()

    return panelId
  }

  /** Restore the empty-state placeholder when the Tabs group has no real
   * panels left. Idempotent — does nothing if `tabs-empty` already exists. */
  const restoreTabsEmptyState = () => {
    const api = dockviewApi.value
    if (!api) return
    if (api.getPanel('tabs-empty')) return

    // Position: try the cached group first; if dockview tore the empty
    // group down with the last panel, recreate it right of chat-panel.
    const tabsGroup = resolveTabsGroup()
    const position = tabsGroup
      ? { referenceGroup: tabsGroup.id, direction: 'within' as const }
      : { referencePanel: 'chat-panel', direction: 'right' as const }

    api.addPanel({
      id: 'tabs-empty',
      component: 'TabsEmptyState',
      title: 'Panels',
      params: {},
      position,
    })

    const grp = api.getPanel('tabs-empty')?.group
    if (grp) {
      tabsGroupId.value = grp.id
      grp.header.hidden = true
    }
  }

  return {
    dockviewApi,
    tabsGroupId,
    tabsCollapsed,
    graphCollapsed,
    setApi,
    setTabsGroupId,
    resolveTabsGroup,
    toggleTabsCollapsed,
    toggleGraphCollapsed,
    collapseTabs,
    expandTabs,
    collapseGraph,
    expandGraph,
    openEnvelopeInTab,
    restoreTabsEmptyState,
    // v2.1 slot-model state + actions
    sideNavCollapsed,
    paneVisibility,
    activePaneId,
    bottomRowSize,
    topRowSplitPct,
    visiblePanes,
    isPaneVisible,
    togglePane,
    setActivePane,
    setBottomRowSize,
    setTopRowSplitPct,
    toggleSideNavCollapsed,
    setSideNavCollapsed,
    resetLayout,
  }
})
