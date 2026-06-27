// Iris Phase 4 Stage 4.2 — PinTile renders a captured envelope + refresh/stale UI.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent, h } from 'vue'
import PrimeVue from 'primevue/config'
import Tooltip from 'primevue/tooltip'
import { createI18n } from 'vue-i18n'

vi.mock('@/catalog/formatCatalog', () => ({
  resolveRenderer: () => defineComponent({ render: () => h('div', { class: 'stub-renderer' }, 'rendered') }),
}))

import PinTile from '@/components/artifacts/PinTile.vue'
import type { ArtifactDto } from '@/types/artifacts'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      artifacts: {
        refreshedAt: 'Updated {at}',
        neverRefreshed: 'not yet refreshed',
        noEnvelope: 'Nothing captured',
        staleError: 'Stale: {error}',
        refresh: 'Refresh',
        remove: 'Delete',
      },
    },
  },
})

function pin(overrides: Partial<ArtifactDto> = {}): ArtifactDto {
  return {
    artifactId: 'a1',
    kind: 'pin',
    name: 'Revenue',
    agentId: 'golem-erp',
    envelope: { bubbleId: 'b1', format: { kind: 2 }, contentJson: '[]' },
    refreshMode: 'manual',
    memberIds: [],
    createdAt: 'now',
    updatedAt: 'now',
    ...overrides,
  }
}

function mountTile(p: ArtifactDto) {
  return mount(PinTile, {
    global: { plugins: [createPinia(), PrimeVue, i18n], directives: { tooltip: Tooltip } },
    props: { pin: p },
  })
}

describe('PinTile', () => {
  it('renders the pin name + agent + the captured envelope', () => {
    const w = mountTile(pin())
    expect(w.find('.pin-tile-name').text()).toBe('Revenue')
    expect(w.find('.pin-tile-agent').text()).toBe('golem-erp')
    expect(w.find('.stub-renderer').exists()).toBe(true)
  })

  it('shows an explicit stale banner when the last refresh failed', () => {
    const w = mountTile(pin({ refreshError: 'pattern timeout' }))
    expect(w.find('.pin-tile-stale').text()).toContain('pattern timeout')
    expect(w.find('.pin-tile').classes()).toContain('pin-tile--error')
  })

  it('emits refresh and remove with the artifact id', async () => {
    const w = mountTile(pin())
    await w.find('.pin-tile-actions button[aria-label="Refresh"]').trigger('click')
    expect(w.emitted('refresh')).toEqual([['a1']])
    await w.find('.pin-tile-actions button[aria-label="Delete"]').trigger('click')
    expect(w.emitted('remove')).toEqual([['a1']])
  })
})
