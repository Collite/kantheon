// @investment/calc — the investment package's canon-functions (§3): TWR/MWR/FIFO. Pure + versioned
// (P-3), authored against the @tatrman/package-sdk SPI shape alone (cert lever; RO-6 shim until ⚑2).

export { twr, mwr, fifo, twrFn, mwrFn, fifoFn } from './calc.js';
export type { SubPeriod, CashFlow, Lot, FifoResult } from './calc.js';
export type { CanonFunction, TypedSignature } from './sdk-shim.js';

import type { CanonFunction } from './sdk-shim.js';
import { twrFn, mwrFn, fifoFn } from './calc.js';

/** The package's registered canon-functions, by id (what the loader installs into the TTR-P fn registry). */
export const canonFunctions: Record<string, CanonFunction> = {
  [twrFn.id]: twrFn,
  [mwrFn.id]: mwrFn,
  [fifoFn.id]: fifoFn,
};
