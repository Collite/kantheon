// FO-P4 — a pure RFC-4180 CSV reader for statement parsing. Header row → column names; each subsequent
// record → a `Record<string, string>`. Real broker exports carry quoted fields (a fund name with a comma,
// a thousands-separated amount), so a bare `split(',')` corrupts every downstream column silently — this
// reader honours double-quoted fields, escaped `""`, and commas/newlines inside quotes. Ragged rows pad to
// the header width; a bounded row cap guards against an unbounded-memory import. Never throws (P-3): a
// malformed structure becomes a diagnostic, not an exception.

export interface CsvDiagnostic {
  /** Machine code — e.g. ROW_CAP, UNTERMINATED_QUOTE. */
  code: string;
  detail: string;
  /** 1-based data-row number where known (structural diagnostics may omit it). */
  row?: number;
}

export interface CsvParseResult {
  rows: Record<string, string>[];
  diagnostics: CsvDiagnostic[];
}

export interface CsvParseOptions {
  /** Cap on data rows (excludes the header). Beyond it, rows are dropped and a ROW_CAP diagnostic is added. */
  maxRows?: number;
}

/** Generous but finite default — a real import is thousands of rows; a million is a memory guard, not a limit. */
const DEFAULT_MAX_ROWS = 1_000_000;

/**
 * Split raw CSV text into records of raw fields (RFC-4180). A field is quoted iff its first character is a
 * `"`; inside quotes, `""` is a literal quote and `,`/newlines are literal. Handles CRLF and LF line
 * endings. Reports an unterminated quote as a diagnostic rather than throwing.
 */
function tokenize(input: string, diagnostics: CsvDiagnostic[]): string[][] {
  const records: string[][] = [];
  let record: string[] = [];
  let field = '';
  let inQuotes = false;
  let atFieldStart = true;
  const n = input.length;

  for (let i = 0; i < n; i++) {
    const c = input[i];
    if (inQuotes) {
      if (c === '"') {
        if (input[i + 1] === '"') {
          field += '"';
          i++; // consume the escaped pair
        } else {
          inQuotes = false;
        }
      } else {
        field += c;
      }
      continue;
    }
    if (c === '"' && atFieldStart) {
      inQuotes = true;
      atFieldStart = false;
      continue;
    }
    if (c === ',') {
      record.push(field);
      field = '';
      atFieldStart = true;
      continue;
    }
    if (c === '\n' || c === '\r') {
      if (c === '\r' && input[i + 1] === '\n') i++; // CRLF → one break
      record.push(field);
      records.push(record);
      record = [];
      field = '';
      atFieldStart = true;
      continue;
    }
    field += c;
    atFieldStart = false;
  }

  if (inQuotes) {
    diagnostics.push({ code: 'UNTERMINATED_QUOTE', detail: 'CSV ended inside a quoted field' });
  }
  // Flush a trailing partial record (file without a final newline). A bare trailing newline leaves an empty
  // pending record, which we intentionally drop.
  if (field !== '' || record.length > 0) {
    record.push(field);
    records.push(record);
  }
  return records;
}

/**
 * Parse RFC-4180 CSV text into header-keyed records plus structural diagnostics. Field values are trimmed
 * (matching the original loader); quote handling happens before the trim, so a quoted `"1,000"` survives as
 * the single value `1,000` for the caller to type-check rather than being shredded across columns.
 */
export function parseCsv(csv: string, opts: CsvParseOptions = {}): CsvParseResult {
  const maxRows = opts.maxRows ?? DEFAULT_MAX_ROWS;
  const diagnostics: CsvDiagnostic[] = [];
  const records = tokenize(csv, diagnostics);
  // Drop wholly-blank records (e.g. a stray empty line) to match the old `.filter(non-blank)` behaviour.
  const nonBlank = records.filter((r) => r.some((f) => f.trim().length > 0));
  if (nonBlank.length === 0) return { rows: [], diagnostics };

  const header = nonBlank[0].map((h) => h.trim());
  const dataRecords = nonBlank.slice(1);

  const capped = dataRecords.length > maxRows;
  const kept = capped ? dataRecords.slice(0, maxRows) : dataRecords;
  if (capped) {
    diagnostics.push({
      code: 'ROW_CAP',
      detail: `import exceeds the ${maxRows}-row cap (${dataRecords.length} data rows); the rest were dropped`,
    });
  }

  const rows = kept.map((cells) =>
    Object.fromEntries(header.map((col, i) => [col, (cells[i] ?? '').trim()])),
  );
  return { rows, diagnostics };
}
