<script setup lang="ts">
// dockview-vue 5.2 host for the v2.1 slot model.
//
// Geometry (spec-fe-2.1 §3):
//
//   ┌───────────────────────┬────────────────────────┐
//   │   Chat (top-left)     │   Tabs (top-right)     │
//   │   pinned              │   pinned, dockable     │
//   ├───────────────────────┴────────────────────────┤
//   │   Bottom panel row (full width)                │
//   │   tab strip + one pane visible at a time       │
//   └────────────────────────────────────────────────┘
//
// Lockdown (`VITE_DOCKVIEW_LOCKDOWN=strict`):
//   1. `disable-floating-groups` — no popout windows.
//   2. Per-group `locked = 'no-drop-target'` on every group — no cross-slot
//      drops; tabs can still reorder *within* the Tabs group.
//   3. Per-group `header.hidden = true` on Chat — no tab strip; nothing to
//      drag, group is effectively undraggable.
//   4. Bottom group keeps `header.hidden = false` (visible tab strip) but is
//      `locked = 'no-drop-target'`; pane visibility is controlled from the
//      View menu, not by drag.
//
// Construction order matters (v2 lesson G-4): chat → bottom row (so the
// row spans the full workspace width) → tabs (which then splits the top row
// horizontally). Doing tabs before the bottom row leaves the row only under
// half the workspace.
import { onBeforeUnmount, watch } from 'vue'
import { DockviewVue, type DockviewReadyEvent } from 'dockview-vue'
import 'dockview-core/dist/styles/dockview.css'
import type { DockviewIDisposable } from 'dockview-core'

import ChatPanel from '@/components/chat/ChatPanel.vue'
import AgentGraphPanel from '@/components/graph/AgentGraphPanel.vue'
import AgentFlowPane from '@/components/panes/AgentFlowPane.vue'
import ArtifactsPanel from '@/components/artifacts/ArtifactsPanel.vue'
import InboxPanel from '@/components/inbox/InboxPanel.vue'
// QueriesPane unwired in the Iris Phase 2 Stage 2.2 BFF re-point (depends on
// metadataService `/metadata/queries`, not served by the BFF). Kept on disk.
import TabsEmptyState from '@/components/tabs/TabsEmptyState.vue'
import PromotedPanel from '@/components/tabs/PromotedPanel.vue'
import TabHeader from '@/components/tabs/TabHeader.vue'
import { useLayoutStore } from '@/stores/layoutStore'
import { useTabsStore } from '@/stores/tabsStore'
import {
  BOTTOM_PANES,
  findPane,
  type BottomPaneId,
} from '@/components/layout/bottomPaneRegistry'

// Register panel + tab components on this instance — dockview-vue's
// `findComponent` walks up `instance.components` looking for the name passed
// to `addPanel({ component, tabComponent })`.
//
// Bottom-row panes are registered under their pane id (`graph`, `flow`, …)
// so the workspace can `addPanel({ component: pane.id })` straight from the
// registry. `defineOptions` requires a static literal (G-6c), so each new
// pane is a one-line addition here.
defineOptions({
  components: {
    ChatPanel,
    TabsEmptyState,
    PromotedPanel,
    TabHeader,
    graph: AgentGraphPanel,
    flow: AgentFlowPane,
    artifacts: ArtifactsPanel,
    inbox: InboxPanel,
  },
})

const layoutStore = useLayoutStore()
const tabsStore = useTabsStore()

const lockdown = (import.meta.env.VITE_DOCKVIEW_LOCKDOWN ?? 'strict') as 'strict' | 'relaxed'
const isStrict = lockdown !== 'relaxed'

const disposables: DockviewIDisposable[] = []

/** Resolve the bottom-group panel by looking at any registered pane that
 *  happens to be mounted. We always keep ≥1 registered pane mounted (the
 *  reconcile logic enforces this), so this almost always finds the group. */
const resolveBottomGroup = () => {
  const api = layoutStore.dockviewApi
  if (!api) return null
  for (const p of BOTTOM_PANES) {
    const panel = api.getPanel(p.id)
    if (panel?.group) return panel.group
  }
  return null
}

/** Add a registered pane into the bottom group. If no bottom group yet,
 *  this *creates* it (full-width row below the top row). Idempotent. */
const mountPane = (id: BottomPaneId) => {
  const api = layoutStore.dockviewApi
  if (!api) return
  if (api.getPanel(id) != null) return

  const pane = findPane(id)
  if (!pane) return

  const bottomGroup = resolveBottomGroup()
  const position = bottomGroup
    ? ({ referenceGroup: bottomGroup.id, direction: 'within' as const })
    : ({ referencePanel: 'chat-panel', direction: 'below' as const })

  api.addPanel({
    id: pane.id,
    component: pane.id,
    title: pane.title,
    position,
  })
}

const unmountPane = (id: BottomPaneId) => {
  const api = layoutStore.dockviewApi
  if (!api) return
  const panel = api.getPanel(id)
  if (panel) panel.api.close()
}

const applyBottomGroupChrome = (collapsed: boolean) => {
  const grp = resolveBottomGroup()
  if (!grp) return
  // When collapsed, hide the tab strip; otherwise show it so the user can
  // switch between visible panes.
  grp.header.hidden = collapsed
  if (isStrict) grp.locked = 'no-drop-target'
}

/** Currently-mounted registered pane ids in registry order. Read directly
 *  from dockview rather than tracked separately — keeps reconcile correct
 *  even if dockview removes a panel by another path. */
const mountedPaneIds = (): BottomPaneId[] => {
  const api = layoutStore.dockviewApi
  if (!api) return []
  return BOTTOM_PANES.map((p) => p.id as BottomPaneId).filter(
    (id) => api.getPanel(id) != null,
  )
}

/** Sync the bottom group to match `layoutStore.visiblePanes`.
 *
 * Strategy (refined after Phase-1 QA): the bottom group is created on
 * demand and torn down when no pane is visible. This makes "all hidden"
 * actually disappear — chat + tabs fill the entire workspace — instead of
 * leaving an empty rectangle behind.
 *
 *  - **Collapse (any → none):** close every registered pane in the bottom
 *    group. dockview's gridview auto-removes the now-empty group and
 *    re-roots the top horizontal branch, so the workspace shows just
 *    `[chat | tabs]` filling the full height.
 *
 *  - **Expand (none → any):** add the first desired pane with
 *    `position: { direction: 'below' }` (no reference). dockview's
 *    "absolute" positioning anchors the new row at the workspace bottom,
 *    spanning full width regardless of the current top-row split. Adding
 *    "below chat-panel" via `referencePanel` would only split chat's
 *    column instead — which is the gridview-restructure bug we hit on
 *    the first attempt. Subsequent panes go `within` the new group.
 *
 *  - **Incremental (within "any visible"):** standard add/remove diff.
 *    The group always has ≥1 panel during these transitions so the
 *    gridview structure is preserved.
 *
 * `setSize` on the bottom group only runs on a *fresh* group (right after
 * the addPanel that created it). Going from a tiny size back up via
 * setSize on an existing group reliably triggers a gridview restructure
 * in dockview-vue 5.2 — recreating the group avoids that path. */
const reconcileBottomPanes = () => {
  const api = layoutStore.dockviewApi
  if (!api) return

  const desired = new Set<BottomPaneId>(
    layoutStore.visiblePanes.map((p) => p.id as BottomPaneId),
  )

  if (desired.size === 0) {
    // Close every registered pane. The bottom group disappears with its
    // last panel; the gridview re-roots and the top row fills the
    // workspace.
    for (const id of mountedPaneIds()) unmountPane(id)
    return
  }

  // Step 1 — ensure the bottom group exists. Pick the registry-first
  // desired pane to be the seed (stable order across toggle cycles).
  if (resolveBottomGroup() == null) {
    const seedId = BOTTOM_PANES.find((p) =>
      desired.has(p.id as BottomPaneId),
    )?.id as BottomPaneId | undefined
    if (seedId) {
      const pane = findPane(seedId)
      if (pane) {
        api.addPanel({
          id: pane.id,
          component: pane.id,
          title: pane.title,
          // Absolute "below" — anchors at the workspace bottom, full width.
          position: { direction: 'below' as const },
        })
      }
    }
  }

  // Step 2 — mount any other desired panes inside the (now-existing) group.
  for (const id of desired) {
    if (api.getPanel(id) == null) mountPane(id)
  }

  // Step 3 — close any mounted panes that aren't desired.
  for (const id of mountedPaneIds()) {
    if (!desired.has(id)) unmountPane(id)
  }

  // Step 4 — chrome + size on the (fresh or pre-existing) bottom group.
  applyBottomGroupChrome(false)
  const bg = resolveBottomGroup()
  if (bg) bg.api.setSize({ height: layoutStore.bottomRowSize })
}

const onReady = (event: DockviewReadyEvent) => {
  const { api } = event
  layoutStore.setApi(api)

  // 1. Chat — anchors the workspace at the left edge.
  api.addPanel({
    id: 'chat-panel',
    component: 'ChatPanel',
    title: 'Chat',
    position: { direction: 'left' },
  })

  // 2. Bottom row — created BEFORE Tabs so it spans the full workspace
  //    width. `reconcileBottomPanes` adds the row only if at least one
  //    pane is currently visible per the persisted state; if every pane
  //    is hidden, no bottom row is created (chat + tabs fill the
  //    workspace below). It also applies the persisted `bottomRowSize`.
  reconcileBottomPanes()

  // 3. Tabs (top-right) — splits the top row only. If a bottom row was
  //    created in step 2 it's already a sibling of the top row, so this
  //    only splits the top horizontally.
  api.addPanel({
    id: 'tabs-empty',
    component: 'TabsEmptyState',
    title: 'Panels',
    position: { referencePanel: 'chat-panel', direction: 'right' },
  })

  // ── Lockdown ────────────────────────────────────────────────────────
  const chatGroup = api.getPanel('chat-panel')?.group
  if (chatGroup && isStrict) {
    chatGroup.header.hidden = true
    chatGroup.locked = 'no-drop-target'
  }

  const tabsGroup = api.getPanel('tabs-empty')?.group
  if (tabsGroup) {
    // Empty placeholder hides its tab; promoted panels later flip the
    // header back on (see layoutStore.openEnvelopeInTab).
    tabsGroup.header.hidden = true
    if (isStrict) tabsGroup.locked = 'no-drop-target'
    layoutStore.setTabsGroupId(tabsGroup.id)
  }

  // ── Initial sizes from the persisted store ──────────────────────────
  // Top-row split: divide chat / tabs per topRowSplitPct. We use width-based
  // sizing on the chat group; dockview re-flows the remainder to tabs.
  if (chatGroup && api.width > 0) {
    const chatWidth = Math.round(api.width * (layoutStore.topRowSplitPct / 100))
    chatGroup.api.setSize({ width: chatWidth })
  }
  // Bottom-row size is set inside `reconcileBottomPanes` based on whether
  // the row is collapsed. No additional setSize needed here.

  // Activate the persisted pane (or fall back to the first visible).
  const activeId = layoutStore.activePaneId
  const firstVisible = layoutStore.visiblePanes[0]?.id ?? null
  const targetActive =
    activeId && layoutStore.isPaneVisible(activeId) ? activeId : firstVisible
  if (targetActive) {
    const panel = api.getPanel(targetActive)
    panel?.api.setActive()
    layoutStore.setActivePane(targetActive)
  }

  // ── Subscriptions ────────────────────────────────────────────────────
  // Phase-4 carry-over: on tab close in the Tabs group, prune tabsStore and
  // restore the empty-state placeholder when the group goes empty.
  disposables.push(
    api.onDidRemovePanel((panel) => {
      // Bottom-row pane closes are driven by the View menu only — but if a
      // user *does* manage to close one (defensive), flip the visibility
      // flag so the store stays in sync.
      const paneId = BOTTOM_PANES.find((p) => p.id === panel.id)?.id
      if (paneId && layoutStore.isPaneVisible(paneId)) {
        layoutStore.togglePane(paneId)
      }

      // Tabs-group cleanup.
      if (panel.id === 'tabs-empty') return
      if (paneId) return

      tabsStore.remove(panel.id)
      if (Object.keys(tabsStore.panels).length === 0) {
        queueMicrotask(() => layoutStore.restoreTabsEmptyState())
      }
    }),
  )

  // Active-tab tracking inside the bottom group: any time dockview changes
  // the active panel, mirror it into the store if it's a registered pane.
  disposables.push(
    api.onDidActivePanelChange((panel) => {
      const id = panel?.id
      const paneId = BOTTOM_PANES.find((p) => p.id === id)?.id
      if (paneId && paneId !== layoutStore.activePaneId) {
        layoutStore.setActivePane(paneId)
      }
    }),
  )

  // Splitter sizes: capture on every layout settle, debounced via the store
  // setters which clamp/round.
  let layoutTimer: number | null = null
  disposables.push(
    api.onDidLayoutChange(() => {
      if (layoutTimer != null) window.clearTimeout(layoutTimer)
      layoutTimer = window.setTimeout(() => {
        layoutTimer = null
        const cg = api.getPanel('chat-panel')?.group
        if (cg && api.width > 0 && cg.width > 0) {
          layoutStore.setTopRowSplitPct((cg.width / api.width) * 100)
        }
        const bg = resolveBottomGroup()
        if (bg && bg.height > 0) {
          layoutStore.setBottomRowSize(bg.height)
        }
      }, 200)
    }),
  )
}

// ── Reactive bridges between layoutStore and dockview ───────────────────
// Pane visibility — single watcher that calls `reconcileBottomPanes` to
// idempotently sync the dockview state to the desired set. Handles:
//   - View-menu toggle on/off
//   - Reset layout (which re-applies defaults to the visibility map)
watch(
  () => layoutStore.visiblePanes.map((p) => p.id),
  () => reconcileBottomPanes(),
  { flush: 'post' },
)

// Active-pane → dockview: when the store's activePaneId changes (e.g. via
// the View menu), nudge dockview to match.
watch(
  () => layoutStore.activePaneId,
  (id) => {
    const api = layoutStore.dockviewApi
    if (!api || !id) return
    const panel = api.getPanel(id)
    if (panel) panel.api.setActive()
  },
  { flush: 'post' },
)

onBeforeUnmount(() => {
  disposables.forEach((d) => d.dispose())
  layoutStore.setApi(null)
})
</script>

<template>
  <div class="dockview-host dockview-theme-light h-full w-full">
    <DockviewVue
      class="h-full w-full"
      :disable-floating-groups="isStrict"
      :disable-tabs-overflow-list="true"
      :hide-borders="true"
      single-tab-mode="default"
      @ready="onReady"
    />
  </div>
</template>

<style scoped>
/* Every built-in dockview theme ships with `--dv-sash-color: transparent`,
 * which leaves the 4px drag handle invisible. We override it (plus the active
 * state) with Aura tokens so the splitters are obvious at rest and clearly
 * highlighted on hover/drag. Also widen via :deep on hover for easier
 * grabbing — dockview's base sash is only 4px and snaps to the cursor in
 * a small region. */
.dockview-host {
  --dv-background-color: var(--p-surface-50);
  --dv-tabs-and-actions-container-background-color: var(--p-surface-100);
  --dv-tab-active-background-color: #fff;
  --dv-tab-inactive-background-color: var(--p-surface-100);
  --dv-sash-color: var(--p-surface-300);
  --dv-active-sash-color: var(--p-primary-500);
  --dv-active-sash-transition-duration: 80ms;
  --dv-active-sash-transition-delay: 0s;
  --dv-separator-border: var(--p-surface-300);
  --dv-paneview-active-outline-color: var(--p-primary-500);
  --dv-icon-color: var(--p-surface-600);
  --dv-icon-hover-background-color: var(--p-surface-200);
  background-color: var(--p-surface-50);
}

.dockview-host :deep(.dv-sash:hover),
.dockview-host :deep(.dv-sash:active) {
  background-color: var(--dv-active-sash-color);
}
.dockview-host :deep(.dv-split-view-container.dv-horizontal > .dv-sash-container > .dv-sash) {
  width: 6px;
}
.dockview-host :deep(.dv-split-view-container.dv-vertical > .dv-sash-container > .dv-sash) {
  height: 6px;
}
</style>
