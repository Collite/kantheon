<script setup lang="ts">
// Stage 07-B B-5 — renders a `pending_clarification` from the v2 envelope.
//
// Options render as a vertical list. The synthetic "Other" entry sits at
// the bottom; clicking it expands a free-text input. On submit (either
// pick or free-text) we call `agentService.resumeClarification(...)` and
// emit the new envelope so the surrounding ChatPanel can append it.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { irisStream } from '@/services/irisStream'
import type { FormatEnvelope, PendingClarification } from '@/types/envelope'

const props = defineProps<{
  threadId: string
  pendingClarification: PendingClarification
}>()

const emit = defineEmits<{
  (e: 'resumed', envelope: FormatEnvelope): void
}>()

const { t } = useI18n()
const toast = useToast()

// A `param_fill` clarification asks the user to type one missing parameter
// value. Its single option only carries the param identity (the `display` is a
// human label, not a value), so we must NOT render it as a clickable answer
// button — clicking would submit the label as the value. Render the free-text
// input directly instead.
const isParamFill = computed(() => props.pendingClarification.kind === 'param_fill')

const otherExpanded = ref(false)
const freeText = ref('')
const submitting = ref(false)

// We surface the first N options up-front and hide the rest behind a "More…"
// affordance; the resolver may return more candidates than fit comfortably.
const INITIAL_VISIBLE_OPTIONS = 5
const showAll = ref(false)
const visibleOptions = computed(() =>
  showAll.value
    ? props.pendingClarification.options
    : props.pendingClarification.options.slice(0, INITIAL_VISIBLE_OPTIONS),
)
const hasMore = computed(
  () => !showAll.value && props.pendingClarification.options.length > INITIAL_VISIBLE_OPTIONS,
)

const submit = async (
  payload: { selectedOptionId?: string; freeTextAnswer?: string },
) => {
  submitting.value = true
  let failed: unknown = null
  try {
    // The BFF resume is SSE (routed to the clarification issuer). Emit the
    // terminal envelope arm; surface an error arm as a toast.
    await irisStream.resumeClarification(
      {
        sessionId: props.threadId,
        resumeToken: props.pendingClarification.resumeToken,
        ...payload,
      },
      {
        onEnvelope: (envelope) => emit('resumed', envelope),
        onError: (e) => { failed = new Error(e.message) },
      },
    )
    if (failed) throw failed
  } catch (err) {
    console.error('[ClarificationCard] resume failed', err)
    toast.add({
      severity: 'error',
      summary: t('errors.agentFailed'),
      detail: err instanceof Error ? err.message : String(err),
      life: 3000,
    })
  } finally {
    submitting.value = false
  }
}

const onPick = (optionId: string) => {
  submit({ selectedOptionId: optionId })
}

const onSubmitFreeText = () => {
  const text = freeText.value.trim()
  if (!text) return
  submit({ freeTextAnswer: text })
}
</script>

<template>
  <div class="clarification-card" role="region" :aria-label="t('chat.clarification.ariaLabel')">
    <ul v-if="!isParamFill" class="options">
      <li v-for="opt in visibleOptions" :key="opt.id">
        <Button
          severity="secondary"
          :label="opt.display"
          :disabled="submitting"
          class="option-btn"
          @click="onPick(opt.id)"
        />
        <p v-if="opt.description" class="option-desc">{{ opt.description }}</p>
      </li>
    </ul>

    <div v-if="!isParamFill && hasMore" class="more-row">
      <Button
        severity="secondary"
        text
        class="other-btn"
        :label="t('chat.clarification.more')"
        :disabled="submitting"
        @click="showAll = true"
      />
    </div>

    <div v-if="!isParamFill" class="other-row">
      <Button
        severity="secondary"
        text
        class="other-btn"
        :label="t('chat.clarification.other')"
        :disabled="submitting"
        @click="otherExpanded = !otherExpanded"
      />
    </div>
    <div v-if="isParamFill || otherExpanded" class="other-input">
      <InputText
        v-model="freeText"
        :placeholder="t('chat.clarification.otherPlaceholder')"
        :disabled="submitting"
        @keydown.enter="onSubmitFreeText"
      />
      <Button
        severity="primary"
        :label="t('chat.clarification.submit')"
        :disabled="submitting || !freeText.trim()"
        @click="onSubmitFreeText"
      />
    </div>
  </div>
</template>

<style scoped>
.clarification-card {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid var(--p-surface-600);
  border-radius: 8px;
  /* Softer grey card instead of the near-black default so it reads as a
   * panel, not an aggressive black block. */
  background: var(--p-surface-700);
}
.options { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.375rem; }
.option-btn { width: 100%; justify-content: flex-start; }
.option-desc { margin: 0.25rem 0 0 0.5rem; font-size: 0.75rem; opacity: 0.7; }
.more-row { display: flex; justify-content: flex-start; }
.other-row { display: flex; justify-content: flex-end; }
/* Keep the "Other…" affordance legible on the dark card: bright idle text with
 * a dimmed dark-red hover, matching the sidebar nav treatment. */
.other-btn.p-button { color: #fff !important; }
.other-btn.p-button:hover { background-color: var(--p-primary-900) !important; color: #fff !important; }
.other-input { display: flex; gap: 0.5rem; align-items: center; }
.other-input :deep(.p-inputtext) { flex: 1; }
</style>
