// FO-P4.S3.T4 — the conseq-distrinfo proposal-source parser (⚑R-2: the re-homed Conseq DistrInfo
// loader). Maps a DistrInfo `ValuationByContract` extract onto the investment model's `position` entity
// (features/midas/conseq/wiki/02-entity-mapping.md) and emits a §5 batch of position proposals — never a
// direct write (FO-8). Bad input becomes a diagnostic, never a throw (P-3: pure + versioned).

import { parseCsv } from './csv.js';
import type { ProposalSourceParser, RowBatch, RowEdit, Diagnostic, ParseContext } from './sdk-shim.js';

const VERSION = '0.1.0';
const TARGET = 'investment.position';

/** DistrInfo `ValuationByContract` column → model `position` field (the Conseq entity mapping). */
const COLUMN_MAP: Record<string, string> = {
  ContractId: 'portfolio',
  ISIN: 'isin',
  ValuationDate: 'valuation_date',
  Holding: 'quantity',
  MarketValue: 'market_value',
};
const KEY_FIELDS = ['portfolio', 'isin', 'valuation_date'];
const NUMERIC_FIELDS = new Set(['quantity', 'market_value']);

export const conseqDistrinfoParser: ProposalSourceParser = {
  id: 'conseq-distrinfo',
  version: VERSION,
  parse(input: Uint8Array, _ctx: ParseContext): { batch: RowBatch; diagnostics: Diagnostic[] } {
    const rows = parseCsv(new TextDecoder().decode(input));
    const diagnostics: Diagnostic[] = [];
    const edits: RowEdit[] = [];

    rows.forEach((raw, idx) => {
      const rowNo = idx + 1;
      const values: Record<string, unknown> = {};
      let dropped = false;

      for (const [col, text] of Object.entries(raw)) {
        const field = COLUMN_MAP[col];
        if (!field) continue; // DistrInfo carries extra columns — ignore the ones we don't map.
        if (NUMERIC_FIELDS.has(field) && text !== '' && Number.isNaN(Number(text))) {
          diagnostics.push({ row: rowNo, code: 'BAD_TYPE', detail: `'${text}' is not numeric for ${field}` });
          dropped = true;
          continue;
        }
        values[field] = text;
      }

      const isin = values.isin;
      if (isin === undefined || String(isin).trim() === '') {
        diagnostics.push({ row: rowNo, code: 'UNKNOWN_ISIN', detail: 'row has no ISIN — cannot resolve the asset' });
        return;
      }
      const missing = KEY_FIELDS.filter((k) => values[k] === undefined || String(values[k]).trim() === '');
      if (missing.length > 0) {
        diagnostics.push({ row: rowNo, code: 'MISSING_KEY', detail: `missing ${missing.join(', ')}` });
        return;
      }
      if (!dropped) edits.push({ op: 'insert', values });
    });

    return {
      batch: {
        target: { ref: TARGET },
        source: { type: 'import', pluginId: 'conseq-distrinfo', pluginVersion: VERSION },
        rows: edits,
      },
      diagnostics,
    };
  },
};
