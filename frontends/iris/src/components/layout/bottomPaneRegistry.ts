// Single source of truth for bottom-panel-row panes. The View menu reads
// this list to render checkable items, the dockview group reads it to add /
// remove panels, and the persistence layer keys off pane `id`s.
//
// Adding a pane in a future phase:
//   1. Add the id to `BottomPaneId`.
//   2. Add an entry to `BOTTOM_PANES`.
//   3. Implement the pane component.
//   4. Register the component on `DockviewWorkspace.defineOptions.components`
//      under the pane id (so dockview can resolve it by name).
// The workspace mounts/unmounts panes from dockview based on visibility, so
// the pane component itself doesn't need to gate rendering — it simply
// disappears from the DOM when toggled off.
import { markRaw, type Component } from 'vue'
import AgentGraphPanel from '@/components/graph/AgentGraphPanel.vue'
import AgentFlowPane from '@/components/panes/AgentFlowPane.vue'
import ArtifactsPanel from '@/components/artifacts/ArtifactsPanel.vue'
import InboxPanel from '@/components/inbox/InboxPanel.vue'
// QueriesPane unwired in the Iris Phase 2 Stage 2.2 BFF re-point (it depends on
// metadataService `/metadata/queries`, which the BFF doesn't serve). The
// component stays on disk; it is simply not registered as a bottom pane.

// Phase-1 only ships `graph`. The other ids are reserved here so the union
// type stays accurate across phases — entries are added to `BOTTOM_PANES` in
// the phase that ships the pane (flow → Phase 2, queries → Phase 4).
// (The `logs` live-log pane was removed — logs are read via Grafana Loki.)
export type BottomPaneId = 'graph' | 'flow' | 'queries' | 'artifacts' | 'inbox'

export interface BottomPane {
  id: BottomPaneId
  /** i18n key for the tab label — wired up properly in Phase 5. */
  titleKey: string
  /** English fallback used until Phase-5 i18n catalogues land. */
  title: string
  /** PrimeIcons class, e.g. `pi pi-sitemap`. */
  icon: string
  /** Whether the pane is visible on first load (when no persisted state). */
  defaultVisible: boolean
  /** The Vue component mounted into the dockview panel. */
  component: Component
}

export const BOTTOM_PANES: BottomPane[] = [
  {
    id: 'graph',
    titleKey: 'panes.graph.title',
    title: 'Graph',
    icon: 'pi pi-sitemap',
    defaultVisible: true,
    component: markRaw(AgentGraphPanel),
  },
  {
    id: 'flow',
    titleKey: 'panes.flow.title',
    title: 'Agent Flow',
    icon: 'pi pi-list',
    defaultVisible: false,
    component: markRaw(AgentFlowPane),
  },
  {
    id: 'artifacts',
    titleKey: 'panes.artifacts.title',
    title: 'Pins & dashboards',
    icon: 'pi pi-bookmark',
    defaultVisible: false,
    component: markRaw(ArtifactsPanel),
  },
  {
    id: 'inbox',
    titleKey: 'panes.inbox.title',
    title: 'Investigations',
    icon: 'pi pi-compass',
    defaultVisible: false,
    component: markRaw(InboxPanel),
  },
]

export const findPane = (id: BottomPaneId): BottomPane | undefined =>
  BOTTOM_PANES.find((p) => p.id === id)

export const defaultPaneVisibility = (): Record<string, boolean> =>
  Object.fromEntries(BOTTOM_PANES.map((p) => [p.id, p.defaultVisible]))
