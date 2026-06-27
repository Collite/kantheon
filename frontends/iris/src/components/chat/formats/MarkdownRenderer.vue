<script setup lang="ts">
// Markdown renderer (Phase 3).
//
// - markdown-it with `html: false` so the agent cannot smuggle raw HTML
//   into the bubble (architecture.md §8 N-SEC-1).
// - Mermaid blocks (` ```mermaid `) render via a custom fence rule. Each
//   diagram gets a unique id so re-renders don't clash.
// - Default `allow_mermaid` / `allow_images` is `true` per the envelope
//   contract — `MarkdownDetails` can override to disable either.
import { computed, nextTick, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import mermaid from 'mermaid'
import type { MarkdownDetails } from '@/types/envelope'

const props = defineProps<{
  text?: string
  details?: MarkdownDetails
}>()

const allowMermaid = computed(() => props.details?.allowMermaid !== false)
const allowImages = computed(() => props.details?.allowImages !== false)

let mermaidInitialised = false
const ensureMermaid = () => {
  if (mermaidInitialised) return
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'default',
    fontFamily: 'inherit',
  })
  mermaidInitialised = true
}

let mermaidIdCounter = 0
const nextMermaidId = () => `mermaid-${Date.now().toString(36)}-${(mermaidIdCounter += 1)}`

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})

const rootRef = ref<HTMLDivElement | null>(null)

const renderedHtml = computed(() => {
  const source = props.text ?? ''
  // Disable image rendering when the envelope opts out.
  const inst = new MarkdownIt({ html: false, linkify: true, breaks: true })
  if (!allowImages.value) {
    inst.renderer.rules.image = () => ''
  }
  // Mermaid fence rule — emit a placeholder div that we render after mount.
  const defaultFence = inst.renderer.rules.fence ?? md.renderer.rules.fence!
  inst.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const token = tokens[idx]
    const info = token?.info?.trim().toLowerCase() ?? ''
    if (allowMermaid.value && info === 'mermaid' && token) {
      const id = nextMermaidId()
      const escaped = token.content
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
      return `<div class="mermaid-host" data-mermaid-id="${id}" data-mermaid-source="${encodeURIComponent(token.content)}"><pre class="mermaid-fallback">${escaped}</pre></div>`
    }
    return defaultFence(tokens, idx, options, env, self)
  }
  return inst.render(source)
})

// After the markdown is in the DOM, render any mermaid placeholders.
const renderMermaid = async () => {
  if (!allowMermaid.value || !rootRef.value) return
  const hosts = rootRef.value.querySelectorAll<HTMLDivElement>('.mermaid-host')
  if (hosts.length === 0) return
  ensureMermaid()
  for (const host of Array.from(hosts)) {
    const id = host.dataset.mermaidId
    const sourceEnc = host.dataset.mermaidSource
    if (!id || !sourceEnc) continue
    try {
      const source = decodeURIComponent(sourceEnc)
      const { svg } = await mermaid.render(id, source)
      host.innerHTML = svg
    } catch (err) {
      console.warn('mermaid render failed', err)
      // Leave the fallback <pre> in place.
    }
  }
}

watch(
  renderedHtml,
  () => {
    void nextTick(() => {
      void renderMermaid()
    })
  },
  { immediate: true },
)
</script>

<template>
  <div ref="rootRef" class="markdown-body" v-html="renderedHtml" />
</template>

<style scoped>
.markdown-body {
  font-size: 0.875rem;
  line-height: 1.55;
  color: inherit;
  word-break: break-word;
}
.markdown-body :deep(p) {
  margin: 0.5em 0;
}
.markdown-body :deep(p:first-child) { margin-top: 0; }
.markdown-body :deep(p:last-child)  { margin-bottom: 0; }
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin-top: 0.75em;
  margin-bottom: 0.25em;
  font-weight: 600;
  line-height: 1.25;
}
.markdown-body :deep(h1) { font-size: 1.15rem; }
.markdown-body :deep(h2) { font-size: 1.05rem; }
.markdown-body :deep(h3) { font-size: 1rem; }
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 1.25em;
  margin: 0.5em 0;
}
.markdown-body :deep(code) {
  background-color: var(--p-surface-100);
  padding: 0.1em 0.3em;
  border-radius: 0.25em;
  font-size: 0.85em;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.markdown-body :deep(pre) {
  background-color: var(--p-surface-100);
  padding: 0.65em 0.85em;
  border-radius: 0.5em;
  overflow-x: auto;
  margin: 0.5em 0;
}
.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
  font-size: 0.78em;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 0.5em 0;
  font-size: 0.85em;
  width: 100%;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--p-surface-200);
  padding: 0.35em 0.6em;
  text-align: left;
}
.markdown-body :deep(th) {
  background-color: var(--p-surface-100);
  font-weight: 600;
}
.markdown-body :deep(.mermaid-host) {
  display: flex;
  justify-content: center;
  margin: 0.5em 0;
  background-color: #fff;
  border: 1px solid var(--p-surface-200);
  border-radius: 0.5em;
  padding: 0.75em;
  overflow-x: auto;
}
.markdown-body :deep(.mermaid-fallback) {
  background-color: var(--p-surface-100);
  padding: 0.5em;
  border-radius: 0.25em;
  font-size: 0.78em;
  color: var(--p-surface-700);
}
.markdown-body :deep(a) {
  color: var(--p-primary-600);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.markdown-body :deep(img) {
  max-width: 100%;
  border-radius: 0.5em;
}
</style>
