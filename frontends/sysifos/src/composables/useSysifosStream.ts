import { useDraftsStore } from '@/stores/drafts'
import { useLoadersStore } from '@/stores/loaders'
import { useSessionStore } from '@/stores/session'
import { config } from '@/config'
import type { LoaderPhase } from '@/stores/loaders'

/**
 * A SysifosStreamEvent as it arrives on the SSE wire (proto-JSON, camelCase keys).
 * Structural (not the generated type) so the dispatch logic is unit-testable
 * without the generated bindings.
 */
export interface StreamEvent {
  heartbeat?: { sessionId?: string }
  draftAck?: { draftId?: string }
  draftCommitted?: { draftId?: string; artifactRef?: string; committedCount?: number; skippedCount?: number }
  draftRejected?: {
    draftId?: string
    reason?: string
    errors?: Array<{ field?: string; code?: string; message?: string; rowIndex?: number }>
  }
  batchRowResult?: {
    draftId?: string
    rowIndex?: number
    outcome?: 'BR_COMMITTED' | 'BR_SKIPPED' | 'BR_FAILED'
    transactionId?: string
    message?: string
  }
  loaderProgress?: { loaderRunId?: string; phase?: string; rowsProcessed?: number; rowsTotal?: number }
  loaderPreviewReady?: { loaderRunId?: string; newCount?: number; duplicateCount?: number; errorCount?: number }
}

/**
 * Subscribes to the BFF `/stream` and fans `SysifosStreamEvent`s into the Pinia
 * stores: heartbeats refresh the session's last-seen; draft events update the
 * drafts store. Uses fetch streaming (not EventSource) so the bearer rides as an
 * `Authorization` header. The connection auto-ends when [signal] aborts.
 */
export function useSysifosStream() {
  const drafts = useDraftsStore()
  const loaders = useLoadersStore()
  const session = useSessionStore()

  function dispatch(event: StreamEvent) {
    if (event.heartbeat) {
      session.lastHeartbeatAt = Date.now()
    } else if (event.draftAck?.draftId) {
      drafts.onAck(event.draftAck.draftId)
    } else if (event.batchRowResult) {
      drafts.onBatchRow(event.batchRowResult)
    } else if (event.draftCommitted) {
      drafts.onCommitted(event.draftCommitted)
    } else if (event.draftRejected) {
      drafts.onRejected(event.draftRejected)
    } else if (event.loaderProgress) {
      loaders.onProgress({ ...event.loaderProgress, phase: event.loaderProgress.phase as LoaderPhase | undefined })
    } else if (event.loaderPreviewReady) {
      loaders.onPreviewReady(event.loaderPreviewReady)
    }
  }

  /** Parse a raw SSE frame block (`event: …\ndata: …`) and dispatch its data line. */
  function handleFrame(frame: string) {
    const dataLine = frame.split('\n').find((l) => l.startsWith('data:'))
    if (!dataLine) return
    try {
      dispatch(JSON.parse(dataLine.slice('data:'.length).trim()) as StreamEvent)
    } catch {
      /* skip malformed frame */
    }
  }

  async function connect(signal?: AbortSignal): Promise<void> {
    const res = await fetch(`${config.bffBase}/stream`, {
      headers: session.bearer ? { Authorization: `Bearer ${session.bearer}` } : {},
      signal,
    })
    if (!res.body) return
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        handleFrame(buffer.slice(0, sep))
        buffer = buffer.slice(sep + 2)
      }
    }
  }

  return { connect, dispatch, handleFrame }
}
