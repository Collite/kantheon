<script setup lang="ts">
// Iris Phase 4 Stage 4.1 — debug-grade hypothesis tree (PD-2). Builds the
// parent_id forest (siblings ordered by display_priority then id) and renders it
// via the self-recursive HypothesisNode. The debugging window into the
// investigator — live hypothesis/execution updates arrive with the Pythia arc.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import HypothesisNode, { type HypTreeNode } from './HypothesisNode.vue'

export interface Hypothesis {
  hypId: string
  parentId?: string
  statement: string
  status: string
  confidence?: number
  rationale?: string
  displayPriority?: number
  testStepIds?: string[]
}

const props = defineProps<{ hypotheses: Hypothesis[] }>()
const { t } = useI18n()

const roots = computed<HypTreeNode[]>(() => {
  const byId = new Map<string, HypTreeNode>()
  props.hypotheses.forEach((h) => byId.set(h.hypId, { ...h, children: [] }))
  const top: HypTreeNode[] = []
  props.hypotheses.forEach((h) => {
    const node = byId.get(h.hypId)!
    const parent = h.parentId ? byId.get(h.parentId) : undefined
    if (parent) parent.children.push(node)
    else top.push(node)
  })
  // Each node already carries `displayPriority` (spread in above) — read it
  // directly instead of re-scanning the source array per comparison (was O(n²)).
  const sort = (ns: HypTreeNode[]) => {
    ns.sort(
      (a, b) =>
        (a.displayPriority ?? 0) - (b.displayPriority ?? 0) || a.hypId.localeCompare(b.hypId),
    )
    ns.forEach((n) => sort(n.children))
  }
  sort(top)
  return top
})
</script>

<template>
  <div class="hyp-tree">
    <h4 class="hyp-title">{{ t('inbox.hypotheses.title') }}</h4>
    <p v-if="hypotheses.length === 0" class="hyp-empty">{{ t('inbox.hypotheses.empty') }}</p>
    <ul v-else class="hyp-list">
      <li v-for="node in roots" :key="node.hypId">
        <HypothesisNode :node="node" />
      </li>
    </ul>
  </div>
</template>

<style scoped>
.hyp-tree {
  font-size: 0.78rem;
  font-family: var(--font-mono, monospace);
}
.hyp-title {
  font-size: 0.8rem;
  margin: 0 0 0.4rem 0;
}
.hyp-empty {
  color: var(--p-surface-500);
  font-style: italic;
}
.hyp-list {
  list-style: none;
  margin: 0;
  padding-left: 0;
}
.hyp-tree > .hyp-list {
  padding-left: 0.2rem;
}
</style>
