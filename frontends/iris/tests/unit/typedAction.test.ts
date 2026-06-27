// Iris Phase 3 Stage 3.2 T5 — the typed-action client (POST /v1/action).
//
// Each builder constructs the contracts §2.4 per-kind payload and dispatches it
// over the shared SSE consumer (irisStream.action). We assert the wire shape
// (kind + payloadJson) and that the same stream handlers fire as a chat turn.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { typedAction } from '@/services/typedAction'
import { irisStream } from '@/services/irisStream'

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.restoreAllMocks()
})

/** Capture the body POSTed to irisStream.action without touching the network. */
function captureAction() {
  const calls: Array<{ sessionId: string; bubbleId?: string; kind: string; payload: unknown }> = []
  vi.spyOn(irisStream, 'action').mockImplementation(async (req, handlers) => {
    calls.push({
      sessionId: req.sessionId,
      bubbleId: req.bubbleId,
      kind: req.action.kind,
      payload: JSON.parse(req.action.payloadJson),
    })
    // Drive the terminal arms so callers can assert their handlers ran.
    handlers.onEnvelope?.({ bubbleId: 'b-1' } as never)
    handlers.onDone?.({ outcome: 'done' } as never)
  })
  return calls
}

const handlers = { onEnvelope: vi.fn(), onDone: vi.fn() }

describe('typedAction — data-shaping kinds', () => {
  it('sort sends {column, direction} with the bubbleId', async () => {
    const calls = captureAction()
    await typedAction.sort(
      { sessionId: 's', bubbleId: 'b-1', column: 'amount', direction: 'desc' },
      handlers,
    )
    expect(calls[0]).toMatchObject({
      sessionId: 's',
      bubbleId: 'b-1',
      kind: 'sort',
      payload: { column: 'amount', direction: 'desc' },
    })
  })

  it('sort defaults direction to asc', async () => {
    const calls = captureAction()
    await typedAction.sort({ sessionId: 's', bubbleId: 'b-1', column: 'name' }, handlers)
    expect((calls[0]!.payload as { direction: string }).direction).toBe('asc')
  })

  it('filter sends {column, operator, value}', async () => {
    const calls = captureAction()
    await typedAction.filter(
      { sessionId: 's', bubbleId: 'b-1', column: 'amount', operator: 'gte', value: 100 },
      handlers,
    )
    expect(calls[0]!.payload).toEqual({ column: 'amount', operator: 'gte', value: 100 })
  })

  it('paginate sends {page, pageSize}', async () => {
    const calls = captureAction()
    await typedAction.paginate(
      { sessionId: 's', bubbleId: 'b-1', page: 3, pageSize: 25 },
      handlers,
    )
    expect(calls[0]!.payload).toEqual({ page: 3, pageSize: 25 })
  })
})

describe('typedAction — navigation + routing kinds', () => {
  it('selectRow sends {rowIndex} with the bubbleId', async () => {
    const calls = captureAction()
    await typedAction.selectRow({ sessionId: 's', bubbleId: 'b-1', rowIndex: 4 }, handlers)
    expect(calls[0]).toMatchObject({ kind: 'select_row', bubbleId: 'b-1', payload: { rowIndex: 4 } })
  })

  it('chipInvocation sends {prompt} and omits patternId when absent', async () => {
    const calls = captureAction()
    await typedAction.chipInvocation({ sessionId: 's', prompt: 'Sales by region' }, handlers)
    expect(calls[0]!.kind).toBe('chip_invocation')
    expect(calls[0]!.payload).toEqual({ prompt: 'Sales by region' })
  })

  it('chipInvocation carries patternId when present', async () => {
    const calls = captureAction()
    await typedAction.chipInvocation(
      { sessionId: 's', prompt: 'Detail', patternId: 'p-7' },
      handlers,
    )
    expect(calls[0]!.payload).toEqual({ prompt: 'Detail', patternId: 'p-7' })
  })

  it('reaskAgent sends {turnId, targetAgentId}', async () => {
    const calls = captureAction()
    await typedAction.reaskAgent(
      { sessionId: 's', turnId: 't-9', targetAgentId: 'golem-sales' },
      handlers,
    )
    expect(calls[0]!.kind).toBe('reask_agent')
    expect(calls[0]!.payload).toEqual({ turnId: 't-9', targetAgentId: 'golem-sales' })
  })

  it('investigate sends {turnId} and carries proposedQuestion when present', async () => {
    const calls = captureAction()
    await typedAction.investigate(
      { sessionId: 's', turnId: 't-9', proposedQuestion: 'Why did margin drop?' },
      handlers,
    )
    expect(calls[0]!.kind).toBe('investigate')
    expect(calls[0]!.payload).toEqual({ turnId: 't-9', proposedQuestion: 'Why did margin drop?' })
  })

  it('drives the caller stream handlers (shared SSE consumer)', async () => {
    captureAction()
    const onEnvelope = vi.fn()
    const onDone = vi.fn()
    await typedAction.sort({ sessionId: 's', bubbleId: 'b-1', column: 'x' }, { onEnvelope, onDone })
    expect(onEnvelope).toHaveBeenCalledOnce()
    expect(onDone).toHaveBeenCalledWith({ outcome: 'done' })
  })
})
