// Terminal-style prompt history for the chat input.
//
// Recall behaviour: ↑ on an empty box brings back the previous question, keep
// pressing ↑ to walk further back, ↓ to walk forward, and ↓ past the newest
// entry restores whatever was mid-typed. History is a flat list of previously
// submitted user questions, persisted to localStorage so it survives reloads
// and new sessions.
//
// State lives here rather than in chatStore because chatStore is cleared by
// `/clear` and `/new`, starts empty on reload, and has no place for the
// navigation cursor + saved draft.

export interface PromptHistory {
  /** Append a submitted question. Trims, ignores empty + consecutive dupes,
   *  caps to MAX_HISTORY, persists, and resets the navigation cursor. */
  record(text: string): void

  /** ArrowUp pressed. `current` = live input value.
   *  Returns the string to put in the input, or `null` to NOT handle the key
   *  (caller lets the event proceed = native caret movement). */
  arrowUp(current: string): string | null

  /** ArrowDown pressed. Returns next value, the restored draft, or `null`. */
  arrowDown(current: string): string | null

  /** Exit navigation mode without changing the input (call on manual edit). */
  reset(): void
}

const MAX_HISTORY = 200

export function usePromptHistory(
  storageKey: string = 'golem.promptHistory.v1',
): PromptHistory {
  const entries: string[] = load(storageKey)
  // cursor === entries.length means "not navigating" (sitting on the live draft).
  let cursor = entries.length
  let savedDraft = ''

  const isNavigating = () => cursor < entries.length

  const reset = (): void => {
    cursor = entries.length
    savedDraft = ''
  }

  const record = (text: string): void => {
    const t = text.trim()
    if (t === '') return
    if (t === entries[entries.length - 1]) {
      // consecutive duplicate — shell behaviour: don't store twice
      reset()
      return
    }
    entries.push(t)
    if (entries.length > MAX_HISTORY) entries.shift()
    persist(storageKey, entries)
    reset()
  }

  const arrowUp = (current: string): string | null => {
    if (!isNavigating() && current.trim() !== '') return null
    if (entries.length === 0) return null
    if (!isNavigating()) {
      savedDraft = current
      cursor = entries.length
    }
    if (cursor > 0) cursor--
    return entries[cursor] ?? null
  }

  const arrowDown = (current: string): string | null => {
    void current
    if (!isNavigating()) return null
    if (cursor < entries.length - 1) {
      cursor++
      return entries[cursor] ?? null
    }
    // past the newest entry → restore the saved draft and exit navigation
    cursor = entries.length
    const draft = savedDraft
    savedDraft = ''
    return draft
  }

  return { record, arrowUp, arrowDown, reset }
}

function load(storageKey: string): string[] {
  try {
    const raw = localStorage.getItem(storageKey)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((x): x is string => typeof x === 'string')
  } catch {
    return []
  }
}

function persist(storageKey: string, entries: string[]): void {
  try {
    localStorage.setItem(storageKey, JSON.stringify(entries))
  } catch {
    // private-mode / quota failures are swallowed — history still works in-memory.
  }
}
