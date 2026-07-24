// FO-P4 — the RFC-4180 CSV reader: quoted fields with commas survive as one value, escaped `""` unquotes,
// ragged rows pad, and an oversized import is capped with a diagnostic (regression: a bare split(',') shifted
// every column on the first quoted comma and corrupted data silently).

import { describe, it, expect } from 'vitest';
import { parseCsv } from '../csv.js';

describe('parseCsv (RFC-4180)', () => {
  it('keeps a comma inside a quoted field as a single value (regression)', () => {
    const { rows, diagnostics } = parseCsv('name,amount\n"Smith, John",100\n');
    expect(diagnostics).toEqual([]);
    expect(rows).toEqual([{ name: 'Smith, John', amount: '100' }]);
  });

  it('unquotes an escaped "" to a single quote', () => {
    const { rows } = parseCsv('note\n"a ""quoted"" word"\n');
    expect(rows).toEqual([{ note: 'a "quoted" word' }]);
  });

  it('pads a ragged/short row to the header width', () => {
    const { rows } = parseCsv('a,b,c\n1,2\n');
    expect(rows).toEqual([{ a: '1', b: '2', c: '' }]);
  });

  it('handles a quoted field with an embedded newline', () => {
    const { rows } = parseCsv('name,note\n"Fund A","line1\nline2"\nX,Y\n');
    expect(rows).toEqual([
      { name: 'Fund A', note: 'line1\nline2' },
      { name: 'X', note: 'Y' },
    ]);
  });

  it('caps the row count and emits a ROW_CAP diagnostic', () => {
    const body = Array.from({ length: 5 }, (_, i) => `${i}`).join('\n');
    const { rows, diagnostics } = parseCsv(`n\n${body}\n`, { maxRows: 2 });
    expect(rows.length).toBe(2);
    expect(diagnostics.some((d) => d.code === 'ROW_CAP')).toBe(true);
  });

  it('returns empty rows (no crash) on empty input', () => {
    expect(parseCsv('')).toEqual({ rows: [], diagnostics: [] });
  });
});
