// Runtime table config: VITE_TABLE_PAGE_SIZE → config.table.pageSize.
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { config } from '@/config'

// config getters read window.APP_CONFIG at access time (no caching), so we can
// drive each case by mutating APP_CONFIG before reading config.table.pageSize.
const setEnv = (vals: Record<string, string | undefined>) => {
  window.APP_CONFIG = { ...window.APP_CONFIG, ...vals }
}

beforeEach(() => {
  window.APP_CONFIG = {}
})
afterEach(() => {
  window.APP_CONFIG = {}
})

describe('config.table.pageSize', () => {
  it('defaults to 25 when unset', () => {
    expect(config.table.pageSize).toBe(25)
  })

  it('honours a runtime override', () => {
    setEnv({ VITE_TABLE_PAGE_SIZE: '50' })
    expect(config.table.pageSize).toBe(50)
  })

  it('floors a fractional value', () => {
    setEnv({ VITE_TABLE_PAGE_SIZE: '20.7' })
    expect(config.table.pageSize).toBe(20)
  })

  it('ignores a non-numeric or non-positive override (falls back to 25)', () => {
    setEnv({ VITE_TABLE_PAGE_SIZE: 'abc' })
    expect(config.table.pageSize).toBe(25)
    setEnv({ VITE_TABLE_PAGE_SIZE: '0' })
    expect(config.table.pageSize).toBe(25)
    setEnv({ VITE_TABLE_PAGE_SIZE: '-5' })
    expect(config.table.pageSize).toBe(25)
  })
})
