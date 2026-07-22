// FO-P4.S3.T5 — the canon-functions against known results + invariants (P-3: pure + versioned).

import { describe, it, expect } from 'vitest';
import { twr, mwr, fifo, twrFn, mwrFn, fifoFn, type SubPeriod, type CashFlow, type Lot } from '../calc.js';

describe('twr (time-weighted return)', () => {
  it('chain-links two +10% sub-periods to +21% (known)', () => {
    const periods: SubPeriod[] = [
      { beginValue: 100, endValue: 110 },
      { beginValue: 110, endValue: 121 },
    ];
    expect(twr(periods)).toBeCloseTo(0.21, 10);
  });

  it('neutralises an external flow: value doubles but half is a deposit → 0% return', () => {
    // begin 100, a 100 deposit lands, end 200 → the return is (200 - 100)/100 - 1 = 0.
    expect(twr([{ beginValue: 100, endValue: 200, cashFlow: 100 }])).toBeCloseTo(0, 10);
  });

  it('is order-independent in magnitude for the chain-link product (invariant)', () => {
    const a: SubPeriod[] = [{ beginValue: 100, endValue: 120 }, { beginValue: 100, endValue: 90 }];
    const b: SubPeriod[] = [{ beginValue: 100, endValue: 90 }, { beginValue: 100, endValue: 120 }];
    expect(twr(a)).toBeCloseTo(twr(b), 10);
  });

  it('skips a zero begin-value sub-period rather than dividing by zero', () => {
    expect(Number.isFinite(twr([{ beginValue: 0, endValue: 10 }, { beginValue: 100, endValue: 110 }]))).toBe(true);
  });
});

describe('mwr (money-weighted return = IRR)', () => {
  it('−100 now, +110 in one period → 10% (known)', () => {
    const flows: CashFlow[] = [
      { amount: -100, time: 0 },
      { amount: 110, time: 1 },
    ];
    expect(mwr(flows)).toBeCloseTo(0.1, 6);
  });

  it('the solved rate zeroes the NPV (invariant)', () => {
    const flows: CashFlow[] = [
      { amount: -100, time: 0 },
      { amount: -50, time: 1 },
      { amount: 170, time: 2 },
    ];
    const r = mwr(flows);
    const npv = flows.reduce((s, f) => s + f.amount / Math.pow(1 + r, f.time), 0);
    expect(npv).toBeCloseTo(0, 6);
  });

  it('returns NaN when there is no sign change (no IRR)', () => {
    expect(Number.isNaN(mwr([{ amount: 100, time: 0 }, { amount: 100, time: 1 }]))).toBe(true);
  });
});

describe('fifo (lot matching)', () => {
  const lots: Lot[] = [
    { qty: 10, price: 5 },
    { qty: 10, price: 6 },
  ];

  it('sells 15 oldest-first: cost basis 80, one partial lot remains (known)', () => {
    const r = fifo(lots, 15);
    expect(r.matched).toEqual([{ qty: 10, price: 5 }, { qty: 5, price: 6 }]);
    expect(r.costBasis).toBe(80);
    expect(r.remaining).toEqual([{ qty: 5, price: 6 }]);
  });

  it('matched quantity equals min(sellQty, held); cost basis is the sum (invariant)', () => {
    const held = lots.reduce((s, l) => s + l.qty, 0);
    for (const sell of [0, 5, 20, 25]) {
      const r = fifo(lots, sell);
      const matchedQty = r.matched.reduce((s, m) => s + m.qty, 0);
      expect(matchedQty).toBe(Math.min(sell, held));
      expect(r.costBasis).toBe(r.matched.reduce((s, m) => s + m.qty * m.price, 0));
    }
  });

  it('does not mutate the input lots', () => {
    const snapshot = JSON.parse(JSON.stringify(lots));
    fifo(lots, 15);
    expect(lots).toEqual(snapshot);
  });
});

describe('CanonFunction wrappers (§3 SPI)', () => {
  it('expose pinned versions + typed signatures and evaluate the pure fns', () => {
    expect(twrFn.version).toBe('0.1.0');
    expect(twrFn.signature.returns).toBe('number');
    expect(twrFn.eval([{ beginValue: 100, endValue: 110 }])).toBeCloseTo(0.1, 10);
    expect(mwrFn.eval([{ amount: -100, time: 0 }, { amount: 110, time: 1 }])).toBeCloseTo(0.1, 6);
    expect(fifoFn.eval([{ qty: 10, price: 5 }, { qty: 10, price: 6 }], 15).costBasis).toBe(80);
  });
});
