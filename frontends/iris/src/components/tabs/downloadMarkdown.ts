// Client-side markdown download for a promoted panel.
//
// Extracted as a module rather than another copy of the anchor/Blob dance —
// which already exists three times in this codebase (ChatInput.downloadBlob,
// TableRenderer.onDownloadCsv, ChartRenderer). Q-19 ruled that `/export` cannot
// be reused here: it is a `<script setup>`-local function that always serialises
// the whole chat store and takes no envelope, so a panel-scoped save is new work.

/** A promoted protocol panel's markdown, exactly as rendered. */
export function downloadMarkdown(markdown: string, filename: string): void {
  // No BOM. ChatInput's exporter prepends one for Excel's benefit on CSV; a
  // protocol is read by humans and diff tools, and a stray U+FEFF would make the
  // downloaded file differ byte-for-byte from the document the server rendered.
  const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** `protocol-<sessionId>-<scopeSlug>.md` (contracts/T4). */
export function protocolFilename(sessionId: string, scopeSlug: string): string {
  return `protocol-${sessionId}-${scopeSlug}.md`
}

/** Fallback filename stem for a panel that did not name itself. */
export function slugify(title: string): string {
  return (
    title
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 60) || 'panel'
  )
}
