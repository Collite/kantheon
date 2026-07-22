// FO-P4.S3.T4 — the conseq-distrinfo parser: a DistrInfo valuation fixture → a §5 position batch +
// diagnostics (golden). Good rows become inserts; a missing ISIN + a bad numeric become diagnostics,
// never a crash. The plugin version is pinned (P-3).

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import { conseqDistrinfoParser } from '../conseqDistrinfo.js';

const fixture = () =>
  readFileSync(fileURLToPath(new URL('./fixtures/conseq-valuation.csv', import.meta.url)));

describe('conseq-distrinfo parser (§13)', () => {
  it('maps a DistrInfo valuation into a §5 position batch (golden)', () => {
    const { batch, diagnostics } = conseqDistrinfoParser.parse(fixture(), { targets: ['investment.position'] });

    expect(batch).toEqual({
      target: { ref: 'investment.position' },
      source: { type: 'import', pluginId: 'conseq-distrinfo', pluginVersion: '0.1.0' },
      rows: [
        {
          op: 'insert',
          values: { portfolio: 'C1', isin: 'LU0000000001', valuation_date: '2026-06-30', quantity: '100', market_value: '10500.00' },
        },
        {
          op: 'insert',
          values: { portfolio: 'C1', isin: 'LU0000000002', valuation_date: '2026-06-30', quantity: '50', market_value: '5000.00' },
        },
      ],
    });

    expect(diagnostics).toEqual([
      { row: 3, code: 'UNKNOWN_ISIN', detail: 'row has no ISIN — cannot resolve the asset' },
      { row: 4, code: 'BAD_TYPE', detail: "'notnum' is not numeric for quantity" },
    ]);
  });

  it('the version is pinned for the entry record (P-3)', () => {
    expect(conseqDistrinfoParser.version).toBe('0.1.0');
  });

  it('never throws on empty input', () => {
    const { batch, diagnostics } = conseqDistrinfoParser.parse(new Uint8Array(), { targets: [] });
    expect(batch.rows).toEqual([]);
    expect(diagnostics).toEqual([]);
  });
});
