// FO-P4 — a small pure CSV reader for statement parsing (RFC-4180-ish; no embedded newlines). Header
// row → column names; each line → a record. Ragged rows pad to the header width.

export function parseCsv(csv: string): Record<string, string>[] {
  const lines = csv
    .split('\n')
    .map((l) => l.replace(/\r$/, ''))
    .filter((l) => l.trim().length > 0);
  if (lines.length === 0) return [];
  const header = lines[0].split(',').map((h) => h.trim());
  return lines.slice(1).map((line) => {
    const cells = line.split(',');
    return Object.fromEntries(header.map((col, i) => [col, (cells[i] ?? '').trim()]));
  });
}
