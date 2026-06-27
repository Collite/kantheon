// Multi-agent config: VITE_GOLEM_AGENTS → config.golemAgents → endpoint routing.
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { config } from '@/config'

// config getters read window.APP_CONFIG at access time (no caching), so we can
// drive each case by mutating APP_CONFIG before reading config.golemAgents.
const setEnv = (vals: Record<string, string | undefined>) => {
  window.APP_CONFIG = { ...window.APP_CONFIG, ...vals }
}

beforeEach(() => {
  window.APP_CONFIG = {}
})
afterEach(() => {
  window.APP_CONFIG = {}
})

describe('config.golemAgents', () => {
  it('falls back to the single "iris" BFF agent when VITE_GOLEM_AGENTS is unset', () => {
    // Stage 2.2 re-point: the single-agent fallback now resolves to the BFF
    // origin (VITE_BFF_BASE_URL), not the legacy golem server.
    setEnv({ VITE_BFF_BASE_URL: 'https://iris-bff.bp-dsk.cz' })
    const agents = config.golemAgents
    expect(agents).toHaveLength(1)
    expect(agents[0]?.id).toBe('iris')
    expect(agents[0]?.baseUrl).toBe('https://iris-bff.bp-dsk.cz')
  })

  it('parses a JSON array and inherits shared VITE_GOLEM_SERVER_* defaults', () => {
    setEnv({
      VITE_GOLEM_SERVER_PROTOCOL: 'https',
      VITE_GOLEM_SERVER_PORT: '', // empty → no port (the df-test https case)
      VITE_GOLEM_AGENTS: JSON.stringify([
        { id: 'ucetnictvi', label: 'Účetnictví', host: 'golem-ucto.aip01.dfpartner.cz' },
        { id: 'sklad', label: 'Sklad', host: 'golem-sklad.aip01.dfpartner.cz' },
      ]),
    })
    const agents = config.golemAgents
    expect(agents.map((a) => a.id)).toEqual(['ucetnictvi', 'sklad'])
    expect(agents[0]?.label).toBe('Účetnictví')
    // protocol inherited from shared default; no port appended.
    expect(agents[0]?.baseUrl).toBe('https://golem-ucto.aip01.dfpartner.cz')
    expect(agents[1]?.baseUrl).toBe('https://golem-sklad.aip01.dfpartner.cz')
  })

  it('lets a per-agent field override the shared default', () => {
    setEnv({
      VITE_GOLEM_SERVER_PROTOCOL: 'https',
      VITE_GOLEM_AGENTS: JSON.stringify([
        { id: 'local', label: 'Local', host: 'localhost', port: '7903', protocol: 'http' },
      ]),
    })
    expect(config.golemAgents[0]?.baseUrl).toBe('http://localhost:7903')
  })

  it('drops entries without a host and falls back to the BFF agent when none are usable', () => {
    setEnv({
      VITE_BFF_BASE_URL: 'https://iris-bff.bp-dsk.cz',
      VITE_GOLEM_AGENTS: JSON.stringify([{ id: 'broken', label: 'No host' }]),
    })
    const agents = config.golemAgents
    expect(agents).toHaveLength(1)
    expect(agents[0]?.id).toBe('iris')
    expect(agents[0]?.baseUrl).toBe('https://iris-bff.bp-dsk.cz')
  })

  it('falls back to the BFF agent on invalid JSON', () => {
    setEnv({ VITE_GOLEM_AGENTS: 'not json{' })
    expect(config.golemAgents.map((a) => a.id)).toEqual(['iris'])
  })
})
