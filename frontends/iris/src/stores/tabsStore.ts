// Phase 4: per-promoted-panel content metadata for the Tabs dock group.
//
// dockview owns layout (which groups exist, panel order, sizes); this store
// owns the *content* of each promoted panel — a deep copy of the source
// bubble's envelope so the tab evolves independently of the chat bubble it
// was opened from (architecture.md §2.7).
//
// `add()` allocates a panelId and stores the deep copy. The DockviewWorkspace
// listens for new entries here and calls `dockview.addPanel` for each.
// `remove()` is called from the workspace's `onDidRemovePanel` listener so
// the metadata follows dockview's lifecycle (close button, drag, etc.).
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DisplayState, FormatEnvelope } from '@/types/envelope'

export interface PromotedPanelData {
  panelId: string
  title: string
  sourceMessageId?: string
  format: FormatEnvelope
  displayState?: DisplayState
}

interface AddInput {
  title: string
  format: FormatEnvelope
  sourceMessageId?: string
  displayState?: DisplayState
}

let counter = 0
const allocatePanelId = (): string => {
  counter += 1
  return `tab_${Date.now().toString(36)}_${counter}`
}

// JSON round-trip — sufficient for a Phase 3/4 envelope (plain JSON shapes,
// no functions or class instances). If we later put non-serialisable values
// in the envelope we'll need a structured-clone here instead.
const deepCopy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

export const useTabsStore = defineStore('tabs', () => {
  const panels = ref<Record<string, PromotedPanelData>>({})

  const add = (input: AddInput): string => {
    const panelId = allocatePanelId()
    panels.value[panelId] = {
      panelId,
      title: input.title,
      sourceMessageId: input.sourceMessageId,
      format: deepCopy(input.format),
      displayState: input.displayState ? deepCopy(input.displayState) : undefined,
    }
    return panelId
  }

  const remove = (panelId: string): void => {
    if (panels.value[panelId]) delete panels.value[panelId]
  }

  /** Replace a panel's envelope (used in v2.1 when the agent re-emits an
   * envelope with `update_tab_id` set; api-contracts.md §5.2). */
  const replace = (panelId: string, format: FormatEnvelope): void => {
    const existing = panels.value[panelId]
    if (!existing) return
    existing.format = deepCopy(format)
  }

  /** Lookup by the *source* bubble id — useful when the BE wants to know
   * "is the bubble I'm about to update currently mirrored in a tab?". */
  const findByBubbleId = (bubbleId: string): PromotedPanelData | undefined => {
    return Object.values(panels.value).find((p) => p.format.bubbleId === bubbleId)
  }

  /** Phase 6: same client-side overlay as the chat bubble — flip a tab's
   * effective view kind (Show-as-Table on a chart in a tab, etc.) without
   * touching the underlying envelope. */
  const updateDisplayState = (panelId: string, partial: DisplayState) => {
    const existing = panels.value[panelId]
    if (!existing) return
    existing.displayState = {
      ...(existing.displayState as Record<string, unknown> | undefined),
      ...partial,
    }
  }

  return { panels, add, remove, replace, findByBubbleId, updateDisplayState }
})
