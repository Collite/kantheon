import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useSessionStore } from '../session'
import { devBearer } from '@/utils/jwt'

describe('session store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('derives user, tenant, and roles from the bearer claims', () => {
    const session = useSessionStore()
    session.setFromToken(devBearer('u1', 'acme', ['midas:write', 'midas:admin']))
    expect(session.isAuthenticated).toBe(true)
    expect(session.userId).toBe('u1')
    expect(session.tenantId).toBe('acme')
    expect(session.hasRole('midas:admin')).toBe(true)
  })

  it('clears the session on logout', () => {
    const session = useSessionStore()
    session.setFromToken(devBearer('u1', 'acme'))
    session.clear()
    expect(session.isAuthenticated).toBe(false)
  })
})
