// @investment/parsers — the investment package's proposal-source parsers (§13). Authored against the
// @tatrman/package-sdk SPI alone (cert lever, FO-23); local shim until the ⚑2 publish cut (RO-6).

export { conseqDistrinfoParser } from './conseqDistrinfo.js';
export { parseCsv } from './csv.js';
export type {
  ProposalSourceParser,
  RowBatch,
  RowEdit,
  BatchSource,
  Diagnostic,
  ParseContext,
} from './sdk-shim.js';

import type { ProposalSourceParser } from './sdk-shim.js';
import { conseqDistrinfoParser } from './conseqDistrinfo.js';

/**
 * excel-book (declared in package.yaml) — the Sysifos Excel import path, a second parser of the same
 * SPI. A registered SEAM: it needs an XLSX parsing library, wired at the publish cut.
 * FO-P4: implement excel-book against an xlsx lib.
 */
export const excelBookParser: ProposalSourceParser = {
  id: 'excel-book',
  version: '0.1.0',
  parse() {
    throw new Error('excel-book parser: not yet implemented (needs an xlsx library) — FO-P4 seam');
  },
};

/** The package's registered proposal-source parsers, by id (what the loader installs into the §13 registry). */
export const parsers: Record<string, ProposalSourceParser> = {
  [conseqDistrinfoParser.id]: conseqDistrinfoParser,
  [excelBookParser.id]: excelBookParser,
};
