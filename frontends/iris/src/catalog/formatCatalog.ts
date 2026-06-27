// Format kind → renderer component map (architecture.md §2.4).
//
// `ChatBubble` and `PromotedPanel` look up the renderer via the bubble's
// `format.kind`. Phase 2 Stage 2.2: `kind` is now the envelope/v1 `FormatKind`
// numeric enum (PLAINTEXT/MARKDOWN/TABLE/CHART), not the old v2 string union.
// Anything unmapped drops through to `UnsupportedRenderer` (FE-3.6).
import type { Component } from 'vue'
import { FormatKind } from '@/types/envelope'
import PlainTextRenderer from '@/components/chat/formats/PlainTextRenderer.vue'
import MarkdownRenderer from '@/components/chat/formats/MarkdownRenderer.vue'
import TableRenderer from '@/components/chat/formats/TableRenderer.vue'
import ChartRenderer from '@/components/chat/formats/ChartRenderer.vue'
import UnsupportedRenderer from '@/components/chat/formats/UnsupportedRenderer.vue'

export const formatCatalog: Partial<Record<FormatKind, Component>> = {
  [FormatKind.PLAINTEXT]: PlainTextRenderer,
  [FormatKind.MARKDOWN]: MarkdownRenderer,
  [FormatKind.TABLE]: TableRenderer,
  [FormatKind.CHART]: ChartRenderer,
}

export const fallbackRenderer = UnsupportedRenderer

export const resolveRenderer = (kind: FormatKind | undefined): Component => {
  if (kind === undefined || kind === FormatKind.FORMAT_KIND_UNSPECIFIED) return PlainTextRenderer
  return formatCatalog[kind] ?? UnsupportedRenderer
}
