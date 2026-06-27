// Iris Phase 4 Stage 4.1 — HypothesisTree builds the parent_id forest (ordered by
// display_priority) and renders nested nodes with status/confidence/step counts.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import HypothesisTree, { type Hypothesis } from '@/components/inbox/HypothesisTree.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      inbox: {
        hypotheses: {
          title: 'Hypotheses',
          empty: 'No hypotheses yet.',
          confidence: 'confidence {value}',
          steps: '{count} steps',
        },
      },
    },
  },
})

function mountTree(hypotheses: Hypothesis[]) {
  return mount(HypothesisTree, { global: { plugins: [i18n] }, props: { hypotheses } })
}

describe('HypothesisTree', () => {
  it('shows the empty state when there are no hypotheses', () => {
    const w = mountTree([])
    expect(w.find('.hyp-empty').exists()).toBe(true)
  })

  it('nests children under their parent and orders siblings by display_priority', () => {
    const hyps: Hypothesis[] = [
      { hypId: 'b', statement: 'Second', status: 'OPEN', displayPriority: 2 },
      { hypId: 'a', statement: 'First', status: 'CONFIRMED', displayPriority: 1, confidence: 0.9 },
      { hypId: 'a1', parentId: 'a', statement: 'Child', status: 'OPEN', testStepIds: ['s1', 's2'] },
    ]
    const w = mountTree(hyps)
    const statements = w.findAll('.hyp-statement').map((n) => n.text())
    // 'First' (priority 1) before 'Second' (priority 2); child rendered nested.
    expect(statements[0]).toBe('First')
    expect(statements).toContain('Child')
    expect(statements).toContain('Second')
    // confidence + step count surfaced
    expect(w.text()).toContain('confidence 0.90')
    expect(w.text()).toContain('2 steps')
  })

  it('renders a child nested inside its parent node', () => {
    const hyps: Hypothesis[] = [
      { hypId: 'a', statement: 'Parent', status: 'OPEN' },
      { hypId: 'a1', parentId: 'a', statement: 'Child', status: 'OPEN' },
    ]
    const w = mountTree(hyps)
    // The nested list lives inside the parent node's subtree.
    expect(w.find('.hyp-node .hyp-list .hyp-statement').text()).toBe('Child')
  })
})
