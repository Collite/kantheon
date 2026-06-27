/** FE-2.10 — Unit tests for chipStore dynamic-chip actions.
 *
 * Covers:
 *   * `setDynamicChips([...])` populates the dynamic chips array.
 *   * `setDynamicChips([])` clears the dynamic chips.
 *   * `clearDynamic()` empties the array.
 *   * Static chips are unaffected by dynamic-chip mutations.
 */

import { setActivePinia, createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useChipStore } from '@/stores/chipStore'
import type { SuggestedChip } from '@/types/chips'

describe('useChipStore — dynamic chips (FE-2.10)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  // -------------------------------------------------------------------------
  // setDynamicChips([...]) populates the array
  // -------------------------------------------------------------------------
  it('setDynamicChips populates dynamicChips', () => {
    const store = useChipStore()
    const chips: SuggestedChip[] = [
      { display: 'Zobraz objednávky', prompt: 'Zobraz objednávky tohoto zákazníka.', source: 'static' },
      { display: 'Zobraz faktury', prompt: 'Zobraz faktury tohoto zákazníka.', source: 'static' },
      { display: 'Nedávná aktivita', prompt: 'Nedávná aktivita tohoto zákazníka.', source: 'static' },
    ]

    store.setDynamicChips(chips)

    expect(store.dynamicChips).toHaveLength(3)
    expect(store.dynamicChips[0]!.display).toBe('Zobraz objednávky')
    expect(store.dynamicChips[1]!.display).toBe('Zobraz faktury')
    expect(store.dynamicChips[2]!.display).toBe('Nedávná aktivita')
  })

  it('setDynamicChips replaces existing dynamic chips', () => {
    const store = useChipStore()

    store.setDynamicChips([{ display: 'Old chip', prompt: 'Old prompt', source: 'static' }])
    store.setDynamicChips([{ display: 'New chip', prompt: 'New prompt', source: 'static' }])

    expect(store.dynamicChips).toHaveLength(1)
    expect(store.dynamicChips[0]!.display).toBe('New chip')
  })

  // -------------------------------------------------------------------------
  // setDynamicChips([]) clears
  // -------------------------------------------------------------------------
  it('setDynamicChips([]) clears the strip', () => {
    const store = useChipStore()
    store.setDynamicChips([{ display: 'Chip', prompt: 'Prompt', source: 'static' }])

    store.setDynamicChips([])

    expect(store.dynamicChips).toHaveLength(0)
  })

  // -------------------------------------------------------------------------
  // clearDynamic() empties the array
  // -------------------------------------------------------------------------
  it('clearDynamic() empties the array', () => {
    const store = useChipStore()
    store.setDynamicChips([{ display: 'Chip', prompt: 'Prompt', source: 'static' }])

    store.clearDynamic()

    expect(store.dynamicChips).toHaveLength(0)
  })

  // -------------------------------------------------------------------------
  // Static chips are unaffected by dynamic-chip mutations
  // -------------------------------------------------------------------------
  it('setDynamicChips does not affect staticChips', () => {
    const store = useChipStore()
    const staticChips: SuggestedChip[] = [
      { display: 'Static chip A', prompt: 'Static prompt A', source: 'static' },
      { display: 'Static chip B', prompt: 'Static prompt B', source: 'static' },
    ]
    store.setStaticChips(staticChips)
    store.setDynamicChips([{ display: 'Dynamic', prompt: 'Dynamic prompt', source: 'static' }])

    expect(store.staticChips).toHaveLength(2)
    expect(store.staticChips[0]!.display).toBe('Static chip A')
  })

  it('clearDynamic does not affect staticChips', () => {
    const store = useChipStore()
    store.setStaticChips([{ display: 'Static', prompt: 'Static prompt', source: 'static' }])
    store.setDynamicChips([{ display: 'Dynamic', prompt: 'Dynamic prompt', source: 'static' }])

    store.clearDynamic()

    expect(store.staticChips).toHaveLength(1)
    expect(store.dynamicChips).toHaveLength(0)
  })

  it('setDynamicChips([]) does not clear static chips', () => {
    const store = useChipStore()
    store.setStaticChips([{ display: 'Static', prompt: 'Static prompt', source: 'static' }])
    store.setDynamicChips([{ display: 'Dynamic', prompt: 'Dynamic prompt', source: 'static' }])

    store.setDynamicChips([])

    expect(store.staticChips).toHaveLength(1)
  })
})
