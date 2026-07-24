// FO-P4 — local shim of @tatrman/package-sdk's proposal-source SPI (§13). The real SDK lives in the
// tatrman repo (Apache, published); RO-6 forbids a cross-repo local link, so until the ⚑2 publish cut
// this package carries the mirror. A package plugin depends on the SDK shape ALONE — the cert lever.
//
// FO-P4: adopt `@tatrman/package-sdk` at the ⚑2 publish cut — RO-6.

export interface RowEdit {
  op: 'insert' | 'update' | 'delete';
  values: Record<string, unknown>;
  key?: Record<string, unknown>;
}

export interface BatchSource {
  type: 'form' | 'import' | 'agent' | 'reconciliation';
  ref?: string;
  pluginId?: string;
  pluginVersion?: string;
}

export interface RowBatch {
  batchId?: string;
  target: { ref: string };
  source: BatchSource;
  rows: RowEdit[];
}

export interface Diagnostic {
  row: number;
  code: string;
  detail: string;
}

export interface ParseContext {
  readonly targets: readonly string[];
}

/** §13 proposal-source parser: bytes → a §5 batch + diagnostics. Pure + versioned; never throws. */
export interface ProposalSourceParser {
  readonly id: string;
  readonly version: string;
  parse(input: Uint8Array, ctx: ParseContext): { batch: RowBatch; diagnostics: Diagnostic[] };
}
