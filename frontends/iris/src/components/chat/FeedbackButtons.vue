<script setup lang="ts">
// Iris Phase 4 Stage 4.3 — turn feedback (PD-3). 👍/👎 on an answer bubble; a 👎
// reveals a one-tap reason picker. Telemetry only — the verdict POSTs to the BFF
// (upsert per turn,user) and never reaches an agent at runtime.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { feedbackApi, type FeedbackReason, type Verdict } from '@/services/feedback'

const props = defineProps<{ turnId: string }>()
const { t } = useI18n()
const toast = useToast()

const verdict = ref<Verdict | null>(null)
const showReasons = ref(false)
const REASONS: FeedbackReason[] = ['wrong_data', 'wrong_agent', 'wrong_format', 'too_slow', 'other']

const send = async (v: Verdict, reason?: FeedbackReason) => {
  // Snapshot the pre-optimistic state so a failed POST can roll the UI back to
  // what the server actually knows (otherwise the badge says 👎 while the server
  // has nothing). `onDown` opens the reason picker optimistically; capture that
  // here, before the optimistic mutation, so the revert is faithful.
  const prevVerdict = verdict.value
  const prevShowReasons = showReasons.value
  // Optimistic: a 👎 reveals the reason picker immediately; a 👍 hides it.
  verdict.value = v
  showReasons.value = v === 'down' && !reason
  try {
    await feedbackApi.submit(props.turnId, v, reason)
    toast.add({ severity: 'success', summary: t('chat.feedback.thanks'), life: 1500 })
  } catch (err) {
    console.error('[FeedbackButtons] submit failed', err)
    verdict.value = prevVerdict
    showReasons.value = prevShowReasons
    toast.add({ severity: 'error', summary: t('chat.feedback.failed'), life: 2500 })
  }
}

const onUp = () => {
  void send('up')
}
const onDown = () => {
  void send('down')
}
const onReason = (reason: FeedbackReason) => {
  showReasons.value = false
  void send('down', reason)
}
</script>

<template>
  <div class="feedback">
    <Button
      text
      size="small"
      :icon="verdict === 'up' ? 'pi pi-thumbs-up-fill' : 'pi pi-thumbs-up'"
      class="fb-btn"
      :class="{ 'fb-active': verdict === 'up' }"
      :aria-label="t('chat.feedback.up')"
      v-tooltip.bottom="t('chat.feedback.up')"
      @click="onUp"
    />
    <Button
      text
      size="small"
      :icon="verdict === 'down' ? 'pi pi-thumbs-down-fill' : 'pi pi-thumbs-down'"
      class="fb-btn"
      :class="{ 'fb-active-down': verdict === 'down' }"
      :aria-label="t('chat.feedback.down')"
      v-tooltip.bottom="t('chat.feedback.down')"
      @click="onDown"
    />
    <div v-if="showReasons" class="fb-reasons" role="group" :aria-label="t('chat.feedback.reasonPrompt')">
      <span class="fb-reason-prompt">{{ t('chat.feedback.reasonPrompt') }}</span>
      <button
        v-for="r in REASONS"
        :key="r"
        type="button"
        class="fb-reason"
        @click="onReason(r)"
      >
        {{ t(`chat.feedback.reasons.${r}`) }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.feedback {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.25rem;
}
.fb-btn :deep(.p-button) {
  width: 1.75rem;
  height: 1.75rem;
}
.fb-active {
  color: var(--p-primary-600);
}
.fb-active-down {
  color: var(--p-red-600, #dc2626);
}
.fb-reasons {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.25rem;
  margin-left: 0.35rem;
}
.fb-reason-prompt {
  font-size: 0.68rem;
  color: var(--p-surface-500);
}
.fb-reason {
  font-size: 0.68rem;
  padding: 0.1rem 0.4rem;
  border: 1px solid var(--p-surface-200);
  border-radius: 0.6rem;
  background: var(--p-surface-50);
  color: var(--p-surface-700);
  cursor: pointer;
}
.fb-reason:hover {
  background: var(--p-primary-50);
  border-color: var(--p-primary-300);
  color: var(--p-primary-700);
}
</style>
