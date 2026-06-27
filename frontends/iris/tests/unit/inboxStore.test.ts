// Iris Phase 4 Stage 4.1 — inbox store ref-counting + polling fallback.
//
// The badge (always mounted) and the panel (mounts/unmounts in the dock) share
// this singleton store and both call startStream/stopStream. These tests guard
// the two regressions: (1) closing the panel must NOT tear down the badge's
// stream (subscriber ref-counting), and (2) a stream that fails / ends without a
// clean close must fall back to polling so the badge can't freeze.
import { setActivePinia, createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useInboxStore } from '@/stores/inboxStore'
import { inboxApi, type InboxView } from '@/services/inbox'

const view = (running: number): InboxView => ({ items: [], counts: { running, needsInput: 0 } })

beforeEach(() => {
  setActivePinia(createPinia())
})
afterEach(() => vi.restoreAllMocks())

describe('useInboxStore — subscriber ref-counting', () => {
  it('keeps the stream open when one of two consumers unsubscribes', () => {
    const close = vi.fn()
    const streamSpy = vi.spyOn(inboxApi, 'stream').mockReturnValue(close)
    const store = useInboxStore()

    // Two consumers subscribe (badge + panel) — only one underlying open.
    store.startStream()
    store.startStream()
    expect(streamSpy).toHaveBeenCalledTimes(1)

    // The panel unmounts: the badge's stream must survive.
    store.stopStream()
    expect(close).not.toHaveBeenCalled()

    // The badge unmounts too: now it closes.
    store.stopStream()
    expect(close).toHaveBeenCalledTimes(1)
  })

  it('does not go negative on an extra stop, and can reopen after', () => {
    const close = vi.fn()
    const streamSpy = vi.spyOn(inboxApi, 'stream').mockReturnValue(close)
    const store = useInboxStore()

    store.startStream()
    store.stopStream()
    store.stopStream() // extra stop must be a no-op (no negative count)
    expect(close).toHaveBeenCalledTimes(1)

    // A later subscribe reopens the stream.
    store.startStream()
    expect(streamSpy).toHaveBeenCalledTimes(2)
  })
})

describe('useInboxStore — polling fallback', () => {
  it('re-polls view() when the stream signals failure while subscribed', async () => {
    // Capture the onFail callback the store hands to inboxApi.stream.
    let onFail: (() => void) | undefined
    vi.spyOn(inboxApi, 'stream').mockImplementation((_onView, fail) => {
      onFail = fail
      return () => {}
    })
    const viewSpy = vi.spyOn(inboxApi, 'view').mockResolvedValue(view(3))
    const store = useInboxStore()

    store.startStream()
    expect(onFail).toBeTypeOf('function')

    onFail!() // stream failed / ended without a clean close
    await Promise.resolve()
    await Promise.resolve()

    expect(viewSpy).toHaveBeenCalled()
    expect(store.running).toBe(3)
  })

  it('does not re-poll after the last consumer has unsubscribed', async () => {
    let onFail: (() => void) | undefined
    vi.spyOn(inboxApi, 'stream').mockImplementation((_onView, fail) => {
      onFail = fail
      return () => {}
    })
    const viewSpy = vi.spyOn(inboxApi, 'view').mockResolvedValue(view(0))
    const store = useInboxStore()

    store.startStream()
    store.stopStream() // no subscribers left
    onFail!() // a late failure callback must not trigger a poll
    await Promise.resolve()

    expect(viewSpy).not.toHaveBeenCalled()
  })
})
