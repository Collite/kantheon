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
 * Bisection anchored at lo = -0.9999, with the upper bound grown geometrically (up to `HI_CAP`) until a
 * sign change is bracketed — robust (no derivative), deterministic, bounded-iteration. Returns NaN when
 * there is genuinely no IRR: an empty series, an all-zero/degenerate series where the NPV never changes
 * sign (e.g. a single flow, or same-sign flows), so no rate zeroes it.
 */
export function mwr(flows: CashFlow[]): number {
  // No cash flows at all → no IRR. (An empty NPV is identically 0; there is nothing to solve.)
  if (flows.length === 0) return NaN;
  const npv = (r: number): number => flows.reduce((s, f) => s + f.amount / Math.pow(1 + r, f.time), 0);
  const lo = -0.9999;
  const flo = npv(lo);
  // A degenerate/flat series — no positive AND no negative flow — has no sign change to bracket, so no
  // IRR exists (all-zero amounts, a single flow, or same-sign flows). npv(lo)===0 with npv(hi)===0 is the
  // all-zero case; a single non-zero flow is strictly one-signed and never crosses zero either.
  const HI_CAP = 1e6;
  // Grow the upper bound geometrically until npv(lo) and npv(hi) straddle zero, or the cap is hit.
  let hi = 10;
  let fhi = npv(hi);
  while (flo * fhi > 0 && hi < HI_CAP) {
    hi = Math.min(hi * 10, HI_CAP);
    fhi = npv(hi);
  }
  // No sign change anywhere in [lo, hi] (incl. the flat/degenerate 0 * 0 case) → no IRR.
  if (flo * fhi > 0 || (flo === 0 && fhi === 0)) return NaN;
  // Bisection on the bracketed [lo, hi].
  let a = lo;
  let fa = flo;
  let b = hi;
  for (let i = 0; i < 200; i++) {
    const mid = (a + b) / 2;
    const fmid = npv(mid);
    if (Math.abs(fmid) < 1e-10) return mid;
    if (fa * fmid < 0) b = mid;
    else {
      a = mid;
      fa = fmid;
    }
  }
  return (a + b) / 2;
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
  /** Unmatched sell quantity — the oversell that the holdings could not cover. 0 when fully matched. */
  shortfall: number;
}

/**
 * Match a sell of `sellQty` against `lots` oldest-first (FIFO), returning the consumed lots, the realized
 * cost basis, and what remains. Selling more than is held consumes everything (matched qty is capped) and
 * reports the uncovered quantity as `shortfall` (> 0 on an oversell) so the caller can detect/act on it.
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
  return { matched, costBasis, remaining: remaining.filter((l) => l.qty > 0), shortfall: Math.max(toMatch, 0) };
}

// ---- Cash-leg derivation helpers (the FO-8 flagship canon calls these) ------------------------

/** Trade operations that move cash OUT of the portfolio (a cash debit). */
const DEBIT_OPERATIONS = new Set(['buy', 'withdrawal', 'fee', 'tax']);
/** Trade operations that move cash INTO the portfolio (a cash credit). */
const CREDIT_OPERATIONS = new Set(['sell', 'deposit', 'dividend', 'interest']);

/**
 * Map a security-leg `operation` to the derived cash leg's direction (the `transaction-entry-apply` canon
 * calls this via `call-fn("cash-operation", operation)`). buy|withdrawal|fee|tax → "debit";
 * sell|deposit|dividend|interest → "credit". An unknown operation throws — the derivation must not guess.
 */
export function cashOperation(op: string): 'debit' | 'credit' {
  if (DEBIT_OPERATIONS.has(op)) return 'debit';
  if (CREDIT_OPERATIONS.has(op)) return 'credit';
  throw new Error(`cash-operation: unknown operation '${op}' — no cash-leg direction defined`);
}

/**
 * Derive the cash leg's traceable external id from the security leg's (the canon calls this via
 * `call-fn("cash-ref", external_id)`). Convention: `<external_id>-cash`.
 */
export function cashRef(externalId: string): string {
  return `${externalId}-cash`;
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

export const cashOperationFn: CanonFunction<[string], 'debit' | 'credit'> = {
  id: 'cash-operation',
  version: '0.1.0',
  signature: { params: [{ name: 'operation', type: 'string' }], returns: 'string' },
  eval: (op) => cashOperation(op),
};

export const cashRefFn: CanonFunction<[string], string> = {
  id: 'cash-ref',
  version: '0.1.0',
  signature: { params: [{ name: 'externalId', type: 'string' }], returns: 'string' },
  eval: (externalId) => cashRef(externalId),
};
