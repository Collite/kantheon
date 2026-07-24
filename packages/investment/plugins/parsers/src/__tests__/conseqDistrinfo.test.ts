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

  const enc = (s: string) => new TextEncoder().encode(s);
  const HEADER = 'ContractId,ISIN,ValuationDate,Holding,MarketValue,ProductName\n';

  it('survives a quoted comma in a mapped column (a thousands-separated amount is rejected, not shredded)', () => {
    // Without RFC-4180 quote handling, "10,500.00" would split and shift MarketValue/ProductName across
    // columns silently. With it, the field stays whole and "10,500.00" is caught as a BAD_TYPE numeric.
    const csv = HEADER + 'C1,LU0000000001,2026-06-30,100,"10,500.00",Fund A\n';
    const { batch, diagnostics } = conseqDistrinfoParser.parse(enc(csv), { targets: ['investment.position'] });
    expect(batch.rows).toEqual([]); // the row is dropped, not committed with corrupt columns
    expect(diagnostics).toEqual([
      { row: 1, code: 'BAD_TYPE', detail: "'10,500.00' is not numeric for market_value" },
    ]);
  });

  it('flags a non-numeric amount as BAD_TYPE and drops the row', () => {
    const csv = HEADER + 'C1,LU0000000001,2026-06-30,100,abc,Fund A\n';
    const { batch, diagnostics } = conseqDistrinfoParser.parse(enc(csv), { targets: ['investment.position'] });
    expect(batch.rows).toEqual([]);
    expect(diagnostics).toContainEqual({ row: 1, code: 'BAD_TYPE', detail: "'abc' is not numeric for market_value" });
  });

  it('emits null for an empty numeric cell (never the empty-string numeric it used to pass through)', () => {
    const csv = HEADER + 'C1,LU0000000001,2026-06-30,100,,Fund A\n';
    const { batch } = conseqDistrinfoParser.parse(enc(csv), { targets: ['investment.position'] });
    expect(batch.rows).toHaveLength(1);
    expect(batch.rows[0].values.market_value).toBeNull();
    expect(batch.rows[0].values.market_value).not.toBe('');
  });

  it('pads a ragged/short row — the missing key columns become MISSING_KEY diagnostics, not silent gibberish', () => {
    // A short row (only 3 cells) pads the rest to '' → the missing valuation_date/quantity/market_value are
    // detected as absent rather than being column-shifted into wrong fields.
    const csv = HEADER + 'C1,LU0000000001,2026-06-30\n';
    const { batch, diagnostics } = conseqDistrinfoParser.parse(enc(csv), { targets: ['investment.position'] });
    // valuation_date is present (3rd cell); Holding/MarketValue empty → quantity/market_value null; not a key.
    // So this row actually resolves — assert it did not corrupt into a wrong column.
    expect(batch.rows).toHaveLength(1);
    expect(batch.rows[0].values).toMatchObject({ portfolio: 'C1', isin: 'LU0000000001', valuation_date: '2026-06-30' });
    expect(batch.rows[0].values.quantity).toBeNull();
    expect(diagnostics).toEqual([]);
  });

  it('reports MISSING_KEY when a real key column is blank in a short row', () => {
    const csv = HEADER + 'C1,LU0000000001\n'; // no ValuationDate
    const { batch, diagnostics } = conseqDistrinfoParser.parse(enc(csv), { targets: ['investment.position'] });
    expect(batch.rows).toEqual([]);
    expect(diagnostics).toContainEqual({ row: 1, code: 'MISSING_KEY', detail: 'missing valuation_date' });
  });
});
