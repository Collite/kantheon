import { describe, it, expect } from 'vitest'
import { groupDiffs, diffKeyOf, isOpen } from '@/validation/reconcile'
import type { ReconcileDiff } from '@/api/types'

const diffs: ReconcileDiff[] = [
  { kind: 'RECON_SYSTEM_ONLY', systemTransaction: { transactionId: 's1' } as never },
  { kind: 'RECON_SYSTEM_ONLY' },
  { kind: 'RECON_STATEMENT_ONLY', status: 'RECON_RESOLVED' },
  { kind: 'RECON_VALUE_MISMATCH', deltas: [{ field: 'quantity', systemValue: '10', statementValue: '12' }] },
]

describe('groupDiffs', () => {
  it('buckets diffs by kind and counts the open ones', () => {
    const g = groupDiffs(diffs)
    expect(g.byKind.RECON_SYSTEM_ONLY).toHaveLength(2)
    expect(g.byKind.RECON_STATEMENT_ONLY).toHaveLength(1)
    expect(g.byKind.RECON_VALUE_MISMATCH).toHaveLength(1)
    expect(g.total).toBe(4)
    expect(g.openCount).toBe(3) // the RESOLVED one is not open
  })

  it('drops decided diffs when openOnly is set', () => {
    const g = groupDiffs(diffs, true)
    expect(g.byKind.RECON_STATEMENT_ONLY).toHaveLength(0) // resolved → hidden
    expect(g.byKind.RECON_SYSTEM_ONLY).toHaveLength(2)
  })
})

describe('diffKeyOf / isOpen', () => {
  it('prefers an explicit id, then a transaction id, then index', () => {
    expect(diffKeyOf({ diffId: 'd-9' }, 0)).toBe('d-9')
    expect(diffKeyOf({ systemTransaction: { transactionId: 's1' } as never }, 2)).toBe('s1')
    expect(diffKeyOf({}, 5)).toBe('idx-5')
  })
  it('treats a missing/RECON_OPEN status as open', () => {
    expect(isOpen({})).toBe(true)
    expect(isOpen({ status: 'RECON_OPEN' })).toBe(true)
    expect(isOpen({ status: 'RECON_RESOLVED' })).toBe(false)
  })
})
