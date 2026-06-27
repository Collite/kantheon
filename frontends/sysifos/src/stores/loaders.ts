import { defineStore } from 'pinia'
import { ref } from 'vue'

export type LoaderPhase = 'LP_PARSING' | 'LP_MAPPING' | 'LP_DIFFING' | 'LP_PREVIEW_READY' | 'LP_COMMITTING' | 'LP_DONE'

export interface LoaderProgressState {
  phase?: LoaderPhase
  rowsProcessed?: number
  rowsTotal?: number
  previewReady?: boolean
  newCount?: number
  duplicateCount?: number
  errorCount?: number
}

/** LoaderRun.status → progress phase (the run-status poll drives the bar in v1). */
const STATUS_TO_PHASE: Record<string, LoaderPhase> = {
  LR_UPLOADED: 'LP_PARSING',
  LR_PARSING: 'LP_PARSING',
  LR_MAPPING: 'LP_MAPPING',
  LR_PREVIEW_READY: 'LP_PREVIEW_READY',
  LR_COMMITTING: 'LP_COMMITTING',
  LR_COMPLETED: 'LP_DONE',
}

/**
 * Tracks Excel-loader run progress. The loader does not push `LoaderProgress` /
 * `LoaderPreviewReady` onto the session SSE bus in v1, so the Import screen polls
 * the run status (`useLoaderRunStatus`) and feeds it here via [applyRun]. The
 * `onProgress` / `onPreviewReady` SSE handlers remain wired for when a push channel
 * lands. The Import screen watches `byRunId[loaderRunId]` to drive the progress bar
 * and navigate to the preview when `previewReady` flips true.
 */
export const useLoadersStore = defineStore('loaders', () => {
  const byRunId = ref<Record<string, LoaderProgressState>>({})

  function onProgress(p: { loaderRunId?: string; phase?: LoaderPhase; rowsProcessed?: number; rowsTotal?: number }) {
    if (!p.loaderRunId) return
    byRunId.value[p.loaderRunId] = {
      ...byRunId.value[p.loaderRunId],
      phase: p.phase,
      rowsProcessed: p.rowsProcessed,
      rowsTotal: p.rowsTotal,
    }
  }

  function onPreviewReady(p: { loaderRunId?: string; newCount?: number; duplicateCount?: number; errorCount?: number }) {
    if (!p.loaderRunId) return
    byRunId.value[p.loaderRunId] = {
      ...byRunId.value[p.loaderRunId],
      previewReady: true,
      newCount: p.newCount,
      duplicateCount: p.duplicateCount,
      errorCount: p.errorCount,
    }
  }

  /** Map a polled `LoaderRun` to progress state (status → phase + preview-ready). */
  function applyRun(run: { loaderRunId?: string; status?: string; rowCountTotal?: number }) {
    if (!run.loaderRunId) return
    const cur = byRunId.value[run.loaderRunId]
    const previewReady = run.status === 'LR_PREVIEW_READY' || run.status === 'LR_COMPLETED'
    byRunId.value[run.loaderRunId] = {
      ...cur,
      phase: (run.status ? STATUS_TO_PHASE[run.status] : undefined) ?? cur?.phase,
      rowsTotal: run.rowCountTotal ?? cur?.rowsTotal,
      // No mid-parse row counter on the run; fill the bar once the preview is ready.
      rowsProcessed: previewReady ? (run.rowCountTotal ?? cur?.rowsProcessed) : cur?.rowsProcessed,
      previewReady: previewReady || cur?.previewReady,
    }
  }

  return { byRunId, onProgress, onPreviewReady, applyRun }
})
