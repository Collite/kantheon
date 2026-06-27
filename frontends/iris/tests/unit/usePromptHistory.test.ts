// Unit tests for the terminal-style prompt-history composable.
// Mirrors the style of chatStore.test.ts. jsdom provides a real localStorage;
// we clear it in beforeEach so each case starts from a clean slate.
import { beforeEach, describe, expect, it } from 'vitest'
import { usePromptHistory } from '@/composables/usePromptHistory'

const KEY = 'golem.promptHistory.v1'

beforeEach(() => {
  localStorage.clear()
})

describe('usePromptHistory', () => {
  it('returns null on an empty history', () => {
    const h = usePromptHistory()
    expect(h.arrowUp('')).toBeNull()
    expect(h.arrowDown('')).toBeNull()
  })

  it('recalls newest first, then older, clamped at the oldest', () => {
    const h = usePromptHistory()
    h.record('a')
    h.record('b')

    expect(h.arrowUp('')).toBe('b')
    expect(h.arrowUp('')).toBe('a')
    expect(h.arrowUp('')).toBe('a') // clamped at oldest
  })

  it('does not recall when the input is non-empty and not yet navigating', () => {
    const h = usePromptHistory()
    h.record('a')
    h.record('b')

    expect(h.arrowUp('draft')).toBeNull()
  })

  it('arrowDown is a no-op when not navigating', () => {
    const h = usePromptHistory()
    h.record('a')

    expect(h.arrowDown('')).toBeNull()
  })

  it('restores the saved draft (empty) when walking past the newest entry', () => {
    const h = usePromptHistory()
    h.record('a')
    h.record('b')

    // navigation can only begin from an empty box → saved draft is ''
    expect(h.arrowUp('')).toBe('b')
    expect(h.arrowUp('')).toBe('a')
    // walk back down
    expect(h.arrowDown('a')).toBe('b')
    // past the newest → restore the original (empty) draft and exit navigation
    expect(h.arrowDown('b')).toBe('')
    // now no longer navigating
    expect(h.arrowDown('')).toBeNull()
  })

  it('does not store consecutive duplicates', () => {
    const h = usePromptHistory()
    h.record('a')
    h.record('a')

    expect(h.arrowUp('')).toBe('a')
    expect(h.arrowUp('')).toBe('a') // still only one entry → clamped
  })

  it('trims and ignores empty submissions', () => {
    const h = usePromptHistory()
    h.record('   ')
    expect(h.arrowUp('')).toBeNull()

    h.record('  hi  ')
    expect(h.arrowUp('')).toBe('hi')
  })

  it('caps history at MAX_HISTORY, dropping the oldest', () => {
    const h = usePromptHistory()
    for (let i = 0; i < 205; i++) h.record(`q${i}`)

    // newest retained
    expect(h.arrowUp('')).toBe('q204')
    // walk all the way back — should stop at q5 (oldest 5 dropped)
    let oldest = ''
    for (let i = 0; i < 250; i++) oldest = h.arrowUp('') as string
    expect(oldest).toBe('q5')
  })

  it('persists across instances sharing the same key', () => {
    const a = usePromptHistory()
    a.record('x')

    const b = usePromptHistory()
    expect(b.arrowUp('')).toBe('x')
  })

  it('loads empty (no throw) from malformed storage', () => {
    localStorage.setItem(KEY, 'not json')
    const h = usePromptHistory()
    expect(h.arrowUp('')).toBeNull()
  })

  it('loads empty when stored value is not an array', () => {
    localStorage.setItem(KEY, JSON.stringify({ foo: 'bar' }))
    const h = usePromptHistory()
    expect(h.arrowUp('')).toBeNull()
  })

  it('reset() exits navigation without changing the input', () => {
    const h = usePromptHistory()
    h.record('a')
    h.record('b')

    expect(h.arrowUp('')).toBe('b') // navigating
    h.reset()
    // back to not-navigating: arrowDown is a no-op again
    expect(h.arrowDown('')).toBeNull()
    // and arrowUp from empty recalls newest again
    expect(h.arrowUp('')).toBe('b')
  })

  it('record() resets the navigation cursor', () => {
    const h = usePromptHistory()
    h.record('a')
    h.record('b')
    h.arrowUp('') // start navigating

    h.record('c') // a fresh submission must reset navigation
    expect(h.arrowDown('')).toBeNull() // not navigating
    expect(h.arrowUp('')).toBe('c')
  })
})
