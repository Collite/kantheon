import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useSysifosStream } from '../useSysifosStream'
import { useDraftsStore } from '@/stores/drafts'
import { useLoadersStore } from '@/stores/loaders'
import { useSessionStore } from '@/stores/session'

describe('useSysifosStream', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('routes a DraftCommitted event into the drafts store', () => {
    const drafts = useDraftsStore()
    const { dispatch } = useSysifosStream()
    dispatch({ draftCommitted: { draftId: 'd1', artifactRef: 'client-123', committedCount: 1, skippedCount: 0 } })
    expect(drafts.byId['d1']?.status).toBe('COMMITTED')
    expect(drafts.byId['d1']?.artifactRef).toBe('client-123')
  })

  it('routes a DraftRejected event with field errors', () => {
    const drafts = useDraftsStore()
    const { dispatch } = useSysifosStream()
    dispatch({ draftRejected: { draftId: 'd2', reason: 'VALIDATION_FAILED', errors: [{ field: 'name', code: 'required' }] } })
    expect(drafts.byId['d2']?.status).toBe('REJECTED')
    expect(drafts.byId['d2']?.errors?.[0]?.field).toBe('name')
  })

  it('routes per-row BatchRowResult events and preserves them when DraftCommitted arrives', () => {
    const drafts = useDraftsStore()
    const { dispatch } = useSysifosStream()
    dispatch({ batchRowResult: { draftId: 'b1', rowIndex: 0, outcome: 'BR_COMMITTED' } })
    dispatch({ batchRowResult: { draftId: 'b1', rowIndex: 1, outcome: 'BR_FAILED', message: 'price required' } })
    dispatch({ draftCommitted: { draftId: 'b1', committedCount: 1, skippedCount: 0 } })
    expect(drafts.byId['b1']?.status).toBe('COMMITTED')
    expect(drafts.byId['b1']?.rows?.[0]?.outcome).toBe('BR_COMMITTED')
    expect(drafts.byId['b1']?.rows?.[1]?.outcome).toBe('BR_FAILED')
    expect(drafts.byId['b1']?.rows?.[1]?.message).toBe('price required')
  })

  it('routes LoaderProgress and LoaderPreviewReady into the loaders store', () => {
    const loaders = useLoadersStore()
    const { dispatch } = useSysifosStream()
    dispatch({ loaderProgress: { loaderRunId: 'r1', phase: 'LP_DIFFING', rowsProcessed: 5, rowsTotal: 10 } })
    expect(loaders.byRunId['r1']?.rowsProcessed).toBe(5)
    dispatch({ loaderPreviewReady: { loaderRunId: 'r1', newCount: 3, duplicateCount: 1, errorCount: 0 } })
    expect(loaders.byRunId['r1']?.previewReady).toBe(true)
    expect(loaders.byRunId['r1']?.newCount).toBe(3)
  })

  it('refreshes the session heartbeat timestamp', () => {
    const session = useSessionStore()
    const { dispatch } = useSysifosStream()
    expect(session.lastHeartbeatAt).toBeNull()
    dispatch({ heartbeat: { sessionId: 's1' } })
    expect(session.lastHeartbeatAt).not.toBeNull()
  })

  it('parses a raw SSE data frame', () => {
    const drafts = useDraftsStore()
    const { handleFrame } = useSysifosStream()
    handleFrame('event: draft_committed\ndata: {"draftCommitted":{"draftId":"d3","artifactRef":"c3"}}')
    expect(drafts.byId['d3']?.status).toBe('COMMITTED')
  })
})
