<script setup lang="ts">
// Slash-command floating popup (FE-7.1).
//
// `commands` is the filtered list (already narrowed by the parent based
// on the input text). Selection is bidirectional so arrow keys in the
// parent can drive the highlight.
//
// We deliberately use a simple absolute-positioned panel instead of
// PrimeVue's `<OverlayPanel>` — the popup needs to stay visible while
// the input below has focus, which `<OverlayPanel>` fights with. A bare
// teleported div anchored to the input is simpler and avoids fighting
// PrimeVue's overlay focus management.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { SlashCommandSpec } from '@/components/chat/slashCommands'

const { t } = useI18n()

const props = defineProps<{
  commands: SlashCommandSpec[]
  visible: boolean
  highlightedIndex: number
}>()

const emit = defineEmits<{
  (e: 'select', spec: SlashCommandSpec): void
  (e: 'update:highlightedIndex', value: number): void
}>()

const visibleCommands = computed(() => props.commands)

const onMouseEnter = (index: number) => {
  emit('update:highlightedIndex', index)
}

const onClick = (spec: SlashCommandSpec) => {
  emit('select', spec)
}
</script>

<template>
  <div
    v-if="visible && visibleCommands.length > 0"
    class="slash-popup"
    role="listbox"
    aria-label="Slash commands"
  >
    <div
      v-for="(cmd, idx) in visibleCommands"
      :key="cmd.name"
      class="slash-row"
      :class="{ 'is-highlighted': idx === highlightedIndex }"
      role="option"
      :aria-selected="idx === highlightedIndex"
      @mouseenter="onMouseEnter(idx)"
      @mousedown.prevent="onClick(cmd)"
    >
      <span class="slash-name">/{{ cmd.name }}</span>
      <span v-if="cmd.argHint" class="slash-arg">{{ cmd.argHint }}</span>
      <span class="slash-desc">{{ t(cmd.descriptionKey) }}</span>
    </div>
  </div>
</template>

<style scoped>
.slash-popup {
  position: absolute;
  bottom: calc(100% + 0.4rem);
  left: 0;
  right: 0;
  max-height: 18rem;
  overflow-y: auto;
  background-color: #fff;
  border: 1px solid var(--p-surface-200);
  border-radius: 0.5rem;
  box-shadow: 0 8px 24px -8px rgba(15, 23, 42, 0.15);
  z-index: 50;
  padding: 0.25rem;
}
.slash-row {
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.65rem;
  border-radius: 0.375rem;
  cursor: pointer;
  font-size: 0.8rem;
  color: var(--p-surface-700);
  transition: background-color 100ms ease;
}
.slash-row.is-highlighted {
  background-color: var(--p-primary-50);
  color: var(--p-primary-800);
}
.slash-name {
  font-weight: 600;
  color: var(--p-primary-700);
}
.slash-row.is-highlighted .slash-name {
  color: var(--p-primary-800);
}
.slash-arg {
  color: var(--p-surface-500);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.72rem;
}
.slash-desc {
  color: inherit;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
