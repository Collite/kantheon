// FO-P4.S3.T5 — the investment package's exotic calc as pure, versioned functions (the calc "beyond
// TTR-P"). Deterministic (P-3): no I/O, no clock. Each is exposed as a §3 CanonFunction below.

import type { CanonFunction } from './sdk-shim.js';

// ---- TWR: time-weighted return ----------------------------------------------------------------

/** A sub-period: the portfolio value at start/end and any external cash flow within it (flow at end). */
export interface SubPeriod {
  beginValue: number;
  endValue: number;
  cashFlow?: number;
}

/**
 * Time-weighted return — chain-links the sub-period returns, neutralising the timing/size of external
 * flows. Each sub-period return is `(endValue - cashFlow) / beginValue - 1`; TWR = ∏(1 + r_i) - 1.
 * A zero begin-value sub-period is skipped (undefined return), not division-by-zero.
 */
export function twr(periods: SubPeriod[]): number {
  const product = periods.reduce((acc, p) => {
    if (p.beginValue === 0) return acc;
    const r = (p.endValue - (p.cashFlow ?? 0)) / p.beginValue - 1;
    return acc * (1 + r);
  }, 1);
  return product - 1;
}

// ---- MWR: money-weighted return (IRR) ---------------------------------------------------------

/** A dated cash flow — `amount` (sign: outflow negative, inflow positive), `time` in periods (e.g. years). */
export interface CashFlow {
  amount: number;
  time: number;
}

/**
 * Money-weighted return = the internal rate of return: the `r` solving Σ amount / (1+r)^time = 0.
 * Bisection on [-0.9999, 10] — robust (no derivative), returns NaN if there is no sign change in range.
 */
export function mwr(flows: CashFlow[]): number {
  const npv = (r: number): number => flows.reduce((s, f) => s + f.amount / Math.pow(1 + r, f.time), 0);
  let lo = -0.9999;
  let hi = 10;
  let flo = npv(lo);
  if (flo * npv(hi) > 0) return NaN;
  for (let i = 0; i < 200; i++) {
    const mid = (lo + hi) / 2;
    const fmid = npv(mid);
    if (Math.abs(fmid) < 1e-10) return mid;
    if (flo * fmid < 0) hi = mid;
    else {
      lo = mid;
      flo = fmid;
    }
  }
  return (lo + hi) / 2;
}

// ---- FIFO: first-in-first-out lot matching ----------------------------------------------------

/** A purchase lot — a quantity bought at a unit price. */
export interface Lot {
  qty: number;
  price: number;
}

export interface FifoResult {
  /** The lots (or partial lots) consumed to satisfy the sell, oldest first. */
  matched: Lot[];
  /** The realized cost basis of the matched quantity (Σ qty × price). */
  costBasis: number;
  /** The lots left after the sell (partial first lot if split). */
  remaining: Lot[];
}

/**
 * Match a sell of `sellQty` against `lots` oldest-first (FIFO), returning the consumed lots, the realized
 * cost basis, and what remains. Selling more than is held consumes everything (matched qty is capped).
 */
export function fifo(lots: Lot[], sellQty: number): FifoResult {
  const remaining = lots.map((l) => ({ ...l }));
  const matched: Lot[] = [];
  let toMatch = sellQty;
  let costBasis = 0;
  for (const lot of remaining) {
    if (toMatch <= 0) break;
    const take = Math.min(lot.qty, toMatch);
    if (take <= 0) continue;
    matched.push({ qty: take, price: lot.price });
    costBasis += take * lot.price;
    lot.qty -= take;
    toMatch -= take;
  }
  return { matched, costBasis, remaining: remaining.filter((l) => l.qty > 0) };
}

// ---- CanonFunction wrappers (§3 SPI) ----------------------------------------------------------

export const twrFn: CanonFunction<[SubPeriod[]], number> = {
  id: 'twr',
  version: '0.1.0',
  signature: { params: [{ name: 'periods', type: 'SubPeriod[]' }], returns: 'number' },
  eval: (periods) => twr(periods),
};

export const mwrFn: CanonFunction<[CashFlow[]], number> = {
  id: 'mwr',
  version: '0.1.0',
  signature: { params: [{ name: 'flows', type: 'CashFlow[]' }], returns: 'number' },
  eval: (flows) => mwr(flows),
};

export const fifoFn: CanonFunction<[Lot[], number], FifoResult> = {
  id: 'fifo',
  version: '0.1.0',
  signature: {
    params: [
      { name: 'lots', type: 'Lot[]' },
      { name: 'sellQty', type: 'number' },
    ],
    returns: 'FifoResult',
  },
  eval: (lots, sellQty) => fifo(lots, sellQty),
};
