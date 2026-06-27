<script setup lang="ts">
// Iris Phase 4 Stage 4.1 — one hypothesis node (PD-2). Self-recursive: renders
// its children by referencing itself by name (Vue resolves the filename-based
// component name). Debug-grade: statement + status + confidence + a flat
// test-step count.
import { useI18n } from 'vue-i18n'

export interface HypTreeNode {
  hypId: string
  statement: string
  status: string
  confidence?: number
  rationale?: string
  testStepIds?: string[]
  displayPriority?: number
  children: HypTreeNode[]
}

defineProps<{ node: HypTreeNode }>()
const { t } = useI18n()
</script>

<template>
  <div class="hyp-node">
    <div class="hyp-head">
      <span class="hyp-statement">{{ node.statement }}</span>
      <span class="hyp-status" :class="`hyp-status--${node.status.toLowerCase()}`">{{ node.status }}</span>
      <span v-if="typeof node.confidence === 'number'" class="hyp-conf">
        {{ t('inbox.hypotheses.confidence', { value: node.confidence.toFixed(2) }) }}
      </span>
      <span v-if="node.testStepIds && node.testStepIds.length > 0" class="hyp-steps">
        {{ t('inbox.hypotheses.steps', { count: node.testStepIds.length }) }}
      </span>
    </div>
    <p v-if="node.rationale" class="hyp-rationale">{{ node.rationale }}</p>
    <ul v-if="node.children.length > 0" class="hyp-list">
      <li v-for="child in node.children" :key="child.hypId">
        <HypothesisNode :node="child" />
      </li>
    </ul>
  </div>
</template>

<style scoped>
.hyp-node {
  margin: 0.25rem 0;
}
.hyp-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.4rem;
}
.hyp-statement {
  font-weight: 600;
}
.hyp-status {
  font-size: 0.68rem;
  padding: 0 0.3rem;
  border-radius: 0.3rem;
  background: var(--p-surface-100);
  color: var(--p-surface-700);
}
.hyp-conf,
.hyp-steps {
  font-size: 0.68rem;
  color: var(--p-surface-500);
}
.hyp-rationale {
  margin: 0.1rem 0 0 0;
  color: var(--p-surface-600);
}
.hyp-list {
  list-style: none;
  margin: 0;
  padding-left: 0.9rem;
  border-left: 1px solid var(--p-surface-200);
}
</style>
