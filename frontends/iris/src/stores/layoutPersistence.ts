// Layout persistence for the v2.1 slot model.
//
// Reads from `localStorage` once on init and writes back on every change to
// the persisted slice of `layoutStore`. Writes are debounced (~250 ms) so
// splitter drags don't hammer storage.
//
// Storage shape (all keys versioned together — change `LAYOUT_VERSION`
// whenever the shape evolves):
//
//   localStorage[`golem.layout.v1.${userId ?? 'anon'}`] = JSON.stringify({
//     version: 1,
//     sideNavCollapsed: boolean,
//     paneVisibility:   Record<paneId, boolean>,
//     activePaneId:     paneId | null,
//     bottomRowSize:    number,            // px
//     topRowSplitPct:   number,            // 0–100
//   })
//
// Corruption / version mismatch: discard silently with `console.warn` and
// fall through to defaults. The store's own `resetLayout()` action is the
// canonical way to revert.
import { watch } from 'vue'
import type { useLayoutStore } from '@/stores/layoutStore'
import {
  BOTTOM_PANES,
  defaultPaneVisibility,
  type BottomPaneId,
} from '@/components/layout/bottomPaneRegistry'

const LAYOUT_VERSION = 1
const KEY_PREFIX = 'golem.layout.v1.'
const DEBOUNCE_MS = 250

interface PersistedLayout {
  version: number
  sideNavCollapsed: boolean
  paneVisibility: Record<string, boolean>
  activePaneId: BottomPaneId | null
  bottomRowSize: number
  topRowSplitPct: number
}

const storageKey = (userId: string | null | undefined): string =>
  `${KEY_PREFIX}${userId ?? 'anon'}`

const isValidPaneId = (id: string): id is BottomPaneId =>
  BOTTOM_PANES.some((p) => p.id === id)

/** Validates a parsed blob against the current version, falling back to
 *  defaults for missing keys. Returns `null` on shape / version mismatch so
 *  the caller can clear the storage entry. */
const sanitise = (raw: unknown): PersistedLayout | null => {
  if (!raw || typeof raw !== 'object') return null
  const blob = raw as Record<string, unknown>
  if (blob.version !== LAYOUT_VERSION) return null

  const visibility: Record<string, boolean> = defaultPaneVisibility()
  if (blob.paneVisibility && typeof blob.paneVisibility === 'object') {
    for (const [k, v] of Object.entries(blob.paneVisibility)) {
      // Accept entries for currently-registered panes only — drops any state
      // for panes that have been removed since the blob was written.
      if (isValidPaneId(k) && typeof v === 'boolean') {
        visibility[k] = v
      }
    }
  }

  const activePaneId =
    typeof blob.activePaneId === 'string' && isValidPaneId(blob.activePaneId)
      ? (blob.activePaneId as BottomPaneId)
      : null

  return {
    version: LAYOUT_VERSION,
    sideNavCollapsed:
      typeof blob.sideNavCollapsed === 'boolean' ? blob.sideNavCollapsed : false,
    paneVisibility: visibility,
    activePaneId,
    bottomRowSize:
      typeof blob.bottomRowSize === 'number' && Number.isFinite(blob.bottomRowSize)
        ? blob.bottomRowSize
        : 260,
    topRowSplitPct:
      typeof blob.topRowSplitPct === 'number' && Number.isFinite(blob.topRowSplitPct)
        ? blob.topRowSplitPct
        : 60,
  }
}

const readPersisted = (userId: string | null | undefined): PersistedLayout | null => {
  try {
    const raw = localStorage.getItem(storageKey(userId))
    if (!raw) return null
    const parsed = JSON.parse(raw)
    const sanitised = sanitise(parsed)
    if (!sanitised) {
      console.warn('[layoutPersistence] discarding incompatible layout blob')
      localStorage.removeItem(storageKey(userId))
      return null
    }
    return sanitised
  } catch (err) {
    console.warn('[layoutPersistence] failed to read layout', err)
    try {
      localStorage.removeItem(storageKey(userId))
    } catch {
      /* ignore */
    }
    return null
  }
}

type LayoutStore = ReturnType<typeof useLayoutStore>

/** Hydrate the store from `localStorage` (if present) and start writing
 *  persisted-slice mutations back, debounced.  Idempotent across re-mounts of
 *  the side nav / workspace — call from `App.vue` once after the auth store
 *  is ready, or from a component that owns the layout. */
export const installLayoutPersistence = (
  layoutStore: LayoutStore,
  getUserId: () => string | null | undefined,
): (() => void) => {
  // 1. Read once and apply.
  const initial = readPersisted(getUserId())
  if (initial) {
    layoutStore.setSideNavCollapsed(initial.sideNavCollapsed)
    layoutStore.paneVisibility = initial.paneVisibility
    layoutStore.activePaneId = initial.activePaneId
    layoutStore.bottomRowSize = initial.bottomRowSize
    layoutStore.topRowSplitPct = initial.topRowSplitPct
  }

  // 2. Persist on change. Debounced — splitter drags emit a stream of
  //    updates and we want one write per drag, not one per frame.
  let timer: number | null = null

  const persist = () => {
    if (timer != null) window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      timer = null
      const blob: PersistedLayout = {
        version: LAYOUT_VERSION,
        sideNavCollapsed: layoutStore.sideNavCollapsed,
        paneVisibility: { ...layoutStore.paneVisibility },
        activePaneId: layoutStore.activePaneId,
        bottomRowSize: layoutStore.bottomRowSize,
        topRowSplitPct: layoutStore.topRowSplitPct,
      }
      try {
        localStorage.setItem(storageKey(getUserId()), JSON.stringify(blob))
      } catch (err) {
        console.warn('[layoutPersistence] failed to write layout', err)
      }
    }, DEBOUNCE_MS)
  }

  // Watch the persisted slice. `deep: true` — paneVisibility is a record.
  const stopWatch = watch(
    () => [
      layoutStore.sideNavCollapsed,
      layoutStore.paneVisibility,
      layoutStore.activePaneId,
      layoutStore.bottomRowSize,
      layoutStore.topRowSplitPct,
    ],
    persist,
    { deep: true },
  )

  return () => {
    stopWatch()
    if (timer != null) window.clearTimeout(timer)
  }
}

/** Clear the persisted layout for the current user. Used by the View menu's
 *  "Reset layout" action so the next reload starts fresh too — pairs with
 *  `layoutStore.resetLayout()` which reverts the in-memory state. */
export const clearPersistedLayout = (userId: string | null | undefined) => {
  try {
    localStorage.removeItem(storageKey(userId))
  } catch {
    /* ignore */
  }
}
