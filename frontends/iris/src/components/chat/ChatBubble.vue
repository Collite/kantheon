<script setup lang="ts">
// Phase 3: ChatBubble is now a thin format-aware shell. It looks up a
// renderer in `formatCatalog` based on the bubble's envelope kind and
// hands it the envelope's payload. Header (role + timestamp), footer
// (Copy + Open in Tab), and selection chips are framing — the renderer
// owns the body.
//
// Phase 4 wires "Open in Tab" to the Tabs pane; today the button is
// disabled with a tooltip.
//
// Phase 4: user bubbles get an edit pencil on hover; clicking it switches
// to a textarea with Save/Cancel; Save dispatches edit_resend.
import { computed, nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Chip from 'primevue/chip'
import Textarea from 'primevue/textarea'
import { useToast } from 'primevue/usetoast'
import { resolveRenderer } from '@/catalog/formatCatalog'
import { useLayoutStore } from '@/stores/layoutStore'
import { useChatStore } from '@/stores/chatStore'
import { useChipStore } from '@/stores/chipStore'
import { useAgentSession } from '@/composables/useAgentSession'
import { irisStream } from '@/services/irisStream'
import type { FilterOperator } from '@/services/typedAction'
import ClarificationCard from '@/components/chat/ClarificationCard.vue'
import ChipStrip from '@/components/chat/ChipStrip.vue'
import AgentBadge, { type ReaskCandidate } from '@/components/chat/AgentBadge.vue'
import FeedbackButtons from '@/components/chat/FeedbackButtons.vue'
import { artifactsApi } from '@/services/artifacts'
import { config } from '@/config'
import { FormatKind, envelopeContent } from '@/types/envelope'
import type { DisplayState, FormatEnvelope, InvestigateChip, PromptChip } from '@/types/envelope'
import { promptChipsOf } from '@/types/chips'

interface Props {
  role: 'user' | 'assistant'
  content: string
  options?: string[]
  envelope?: FormatEnvelope
  timestamp?: Date
  messageId?: string
  displayState?: DisplayState
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'option-click', option: string): void
}>()

const toast = useToast()
const layoutStore = useLayoutStore()
const chatStore = useChatStore()
const { t } = useI18n()
const {
  sessionId,
  prompt,
  armSelection,
  editAndResend,
  pickRoutingAgent,
  submitChip,
  investigateTurn,
  reaskAgent,
  sortTable,
  filterTable,
  paginateTable,
  drillRow,
} = useAgentSession()

// Phase 4 — edit mode
const editingMessageId = ref<string | null>(null)
const editedText = ref('')

const kind = computed<FormatKind>(() => {
  if (props.role === 'user') return FormatKind.PLAINTEXT
  const override = (props.displayState as { viewKind?: FormatKind } | undefined)?.viewKind
  return override ?? props.envelope?.format?.kind ?? FormatKind.PLAINTEXT
})

const onDisplayChanged = (state: { viewKind?: FormatKind }) => {
  if (!props.messageId) return
  chatStore.updateDisplayState(props.messageId, state)
}

const effectiveDetails = computed(() => {
  const fmt = props.envelope?.format
  if (!fmt) return undefined
  // When the user has flipped the view kind (e.g. Show-as-Chart) the stored
  // details belong to the original kind, so don't leak them to a different renderer.
  if (fmt.kind && fmt.kind !== kind.value) return undefined
  // envelope/v1 carries per-kind details on the FormatSpec (format.table /
  // format.chart / format.markdown) — no `details` union (dropped from v2).
  return kind.value === FormatKind.TABLE
    ? fmt.table
    : kind.value === FormatKind.CHART
      ? fmt.chart
      : kind.value === FormatKind.MARKDOWN
        ? fmt.markdown
        : undefined
})

const canOpenInTab = computed(() => {
  if (props.role !== 'assistant') return false
  if (!props.envelope) return false
  return kind.value === FormatKind.MARKDOWN || kind.value === FormatKind.TABLE
})

const openInTabTooltip = computed(() => {
  if (!props.envelope) return t('chat.openInTabNoEnvelope')
  if (kind.value === FormatKind.PLAINTEXT) return t('chat.openInTabPlaintext')
  if (kind.value === FormatKind.CHART) return t('chat.openInTabComing', { phase: 6 })
  return t('chat.openInTab')
})

const renderer = computed(() => resolveRenderer(kind.value))

// Stage 3.2 T7 — always-on "Investigate this" on table/chart answers. The BFF
// builds the HandoffContext from the turn it owns; the FE only names the turn to
// escalate (the `investigate` typed action defaults the proposed question).
const canInvestigate = computed(
  () =>
    props.role === 'assistant' &&
    !!props.envelope?.turnId &&
    (kind.value === FormatKind.TABLE || kind.value === FormatKind.CHART),
)

// Only mount the ClarificationCard for a *genuine* clarification. A `param_fill`
// always needs a free-text answer; any other kind is only a clarification when
// it carries at least one option. An empty-options, non-param_fill payload is
// not a question to answer — rendering it would paste the bare "Něco jiného…"
// (Other) box onto every normal table output.
const showClarification = computed(() => {
  const pc = props.envelope?.pendingClarification
  if (!pc) return false
  return pc.kind === 'param_fill' || (pc.options?.length ?? 0) > 0
})

const message = computed(() =>
  props.messageId ? chatStore.messages.find((m) => m.id === props.messageId) : undefined,
)

const renderText = computed(() => {
  return props.envelope?.text ?? props.content
})

const containerClass = computed(() => (props.role === 'user' ? 'justify-end' : 'justify-start'))

const bubbleClass = computed(() => {
  return props.role === 'user'
    ? 'bubble user-bubble'
    : 'bubble assistant-bubble'
})

const showAssistantFooter = computed(
  () => props.role === 'assistant' && (props.content.length > 0 || !!props.envelope),
)

// Phase 4: can edit user messages when not streaming
const canEdit = computed(
  () =>
    props.role === 'user' &&
    props.messageId &&
    !chatStore.streaming &&
    (!chatStore.currentlyEditingId || chatStore.currentlyEditingId === props.messageId),
)

// Phase 4: pencil visibility
const showPencil = computed(() => canEdit.value)

const onCopy = async () => {
  const text = props.envelope?.text ?? props.content
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    toast.add({ severity: 'success', summary: t('chat.copied'), life: 1500 })
  } catch (err) {
    console.warn('Clipboard write failed', err)
    toast.add({ severity: 'warn', summary: t('chat.copyFailed'), life: 2000 })
  }
}

const onOpenInTab = () => {
  if (!props.envelope) return
  const id = layoutStore.openEnvelopeInTab(props.envelope, {
    sourceMessageId: props.messageId,
  })
  if (id) {
    toast.add({ severity: 'success', summary: t('chat.openedInTab'), life: 1500 })
  } else {
    toast.add({ severity: 'warn', summary: t('chat.workspaceNotReady'), life: 2000 })
  }
}

// The BFF has no typed-action channel yet (/v1/action is Phase 3); row-select
// becomes a plain natural-text turn whose question describes the picked row (the
// phrasing keeps Themis/the cascade's planning in the loop). POST /v1/chat/turn
// returns the terminal envelope directly, which we append as a fresh bubble.
const onSelectRow = async (payload: { rowNumber: number; originalMessageId: string }) => {
  void payload.originalMessageId
  try {
    const env = await irisStream.turn({
      sessionId: sessionId.value,
      question: `Vyber řádek ${payload.rowNumber}`,
    })
    if (env) {
      chatStore.appendOrReplaceEnvelope(env, { attachToStreaming: false })
      if (env.chips && env.chips.length > 0) {
        useChipStore().setDynamicChips(promptChipsOf(env.chips))
      }
    }
  } catch (err) {
    console.error('[ChatBubble] select_row failed', err)
    toast.add({ severity: 'error', summary: t('errors.agentFailed'), life: 3000 })
  }
}

// Stage 3.2 T5 — table data-shaping typed actions. Each pairs the renderer's
// directive with this bubble's bubble_id and re-issues over POST /v1/action.
const onTableSort = (p: { column: string; direction: 'asc' | 'desc' }) => {
  const bubbleId = props.envelope?.bubbleId
  if (bubbleId) void sortTable(bubbleId, p.column, p.direction)
}
const onTableFilter = (p: { column: string; operator: string; value: unknown }) => {
  const bubbleId = props.envelope?.bubbleId
  if (bubbleId) void filterTable(bubbleId, p.column, p.operator as FilterOperator, p.value)
}
const onTablePaginate = (p: { page: number; pageSize: number }) => {
  const bubbleId = props.envelope?.bubbleId
  if (bubbleId) void paginateTable(bubbleId, p.page, p.pageSize)
}
const onTableDrilldown = (p: { rowIndex: number }) => {
  const bubbleId = props.envelope?.bubbleId
  if (bubbleId) void drillRow(bubbleId, p.rowIndex)
}

// Row-detail — TableRenderer emits `show-detail` with the picked row's stable
// index. Pair it with this bubble's envelope bubble_id to arm a selection
// reference, then prefill an *editable* prompt (we don't auto-send — the
// selection rides along with whatever the user submits). Focus the shared chat
// input so the user can edit/confirm immediately.
const onShowDetail = ({ rowIndex }: { rowIndex: number }) => {
  const bubbleId = props.envelope?.bubbleId
  if (!bubbleId) return
  armSelection({ bubble_id: bubbleId, row_indices: [rowIndex] })
  prompt.value = t('detail.prefill')
  void nextTick(() => {
    const el = document.getElementById('chat-input') as HTMLInputElement | null
    el?.focus()
  })
}

// Stage 3.2 — chip strip handlers (envelope/v1 Chip oneof).
//
// The user's originating question for a RoutingPickChip re-issue is the nearest
// preceding user bubble (needs_user_pick produced THIS assistant bubble, whose
// chips ask which agent should answer the question above it).
const precedingUserText = computed(() => {
  if (!props.messageId) return ''
  const idx = chatStore.messages.findIndex((m) => m.id === props.messageId)
  for (let i = idx - 1; i >= 0; i--) {
    const m = chatStore.messages[i]
    if (m?.role === 'user' && m.content) return m.content
  }
  return ''
})

const onChipPrompt = async (chip: PromptChip) => {
  try {
    await submitChip(chip.prompt, chip.patternId)
  } catch (err) {
    console.error('[ChatBubble] chip_invocation failed', err)
    toast.add({ severity: 'error', summary: t('errors.agentFailed'), life: 3000 })
  }
}

const onRoutingPick = async (agentId: string) => {
  const question = precedingUserText.value
  if (!question) return
  try {
    await pickRoutingAgent(agentId, question)
  } catch (err) {
    console.error('[ChatBubble] routing pick failed', err)
    toast.add({ severity: 'error', summary: t('errors.agentFailed'), life: 3000 })
  }
}

const onInvestigateChip = async (chip: InvestigateChip) => {
  await dispatchInvestigate(chip.proposedQuestion)
}

// Per-block "Investigate this" — no proposed question; the BFF defaults it.
const onInvestigateBlock = async () => {
  await dispatchInvestigate(undefined)
}

// Stage 4.2 — pin this table/chart bubble as a refreshable artifact (PD-6).
const canPin = computed(
  () =>
    props.role === 'assistant' &&
    !!props.envelope?.turnId &&
    !!props.envelope?.bubbleId &&
    (kind.value === FormatKind.TABLE || kind.value === FormatKind.CHART),
)

const onPin = async () => {
  const env = props.envelope
  if (!env?.turnId || !env.bubbleId) return
  const name = (env.text || t('chat.pin.label')).slice(0, 60)
  try {
    await artifactsApi.createPin({ turnId: env.turnId, bubbleId: env.bubbleId, name })
    toast.add({ severity: 'success', summary: t('chat.pin.pinned'), life: 1500 })
  } catch (err) {
    console.error('[ChatBubble] pin failed', err)
    toast.add({ severity: 'error', summary: t('chat.pin.failed'), life: 3000 })
  }
}

const dispatchInvestigate = async (proposedQuestion: string | undefined) => {
  const turnId = props.envelope?.turnId
  if (!turnId) return
  try {
    await investigateTurn(turnId, proposedQuestion)
  } catch (err) {
    console.error('[ChatBubble] investigate failed', err)
    toast.add({ severity: 'error', summary: t('errors.agentFailed'), life: 3000 })
  }
}

// Stage 3.2 T6 — agent badge + re-ask picker. The badge shows the answering
// agent; the picker re-routes the turn (reask_agent → corrected_agent_id).
const answeringAgent = computed(
  () => props.envelope?.agentId || props.envelope?.agentVersion || '',
)

// Show the badge on answered assistant bubbles (an agent owns it) that carry a
// turn id (so a re-ask can name the turn). Routing-pick / clarification bubbles
// (no agentId) get no badge.
const showAgentBadge = computed(
  () => props.role === 'assistant' && !!props.envelope?.agentId && !!props.envelope?.turnId,
)

// Candidates, pre-sorted by the original RoutingDecision.alternates (the
// envelope's RoutingPickChips carry the per-agent `why`); the configured
// routable agents follow, minus the answering agent and any already listed.
// (An FE capabilities read client for the full role-filtered routable set is a
// follow-up; config.golemAgents stands in until then.)
const reaskCandidates = computed<ReaskCandidate[]>(() => {
  const answering = props.envelope?.agentId
  const alts: ReaskCandidate[] = (props.envelope?.chips ?? []).flatMap((c) =>
    c.routing?.agentId?.value
      ? [{ agentId: c.routing.agentId.value, label: c.routing.label, why: c.routing.why }]
      : [],
  )
  const seen = new Set<string>([...(answering ? [answering] : []), ...alts.map((a) => a.agentId)])
  const fromConfig: ReaskCandidate[] = config.golemAgents
    .filter((a) => a.id && !seen.has(a.id))
    .map((a) => ({ agentId: a.id, label: a.label }))
  return [...alts, ...fromConfig]
})

const onReask = async (agentId: string) => {
  const turnId = props.envelope?.turnId
  if (!turnId) return
  try {
    await reaskAgent(turnId, agentId)
  } catch (err) {
    console.error('[ChatBubble] reask_agent failed', err)
    toast.add({ severity: 'error', summary: t('errors.agentFailed'), life: 3000 })
  }
}

// Stage clarif-resume 01 — ClarificationCard emits `resumed` with the new
// envelope from POST /v2/chat/resume. Append it like any streamed envelope and
// (mirroring useAgentSession.onEnvelope) refresh dynamic chips when present.
const onResumed = (env: FormatEnvelope) => {
  chatStore.appendOrReplaceEnvelope(env, { attachToStreaming: false })
  if (env.chips && env.chips.length > 0) {
    useChipStore().setDynamicChips(promptChipsOf(env.chips))
  }
}

// Phase 4: edit mode handlers
const startEdit = () => {
  if (!props.messageId) return
  chatStore.currentlyEditingId = props.messageId
  editingMessageId.value = props.messageId
  editedText.value = props.content
}

const cancelEdit = () => {
  chatStore.currentlyEditingId = null
  editingMessageId.value = null
  editedText.value = ''
}

const saveEdit = async () => {
  if (!editingMessageId.value || !editedText.value.trim()) {
    cancelEdit()
    return
  }
  const editTargetId = editingMessageId.value
  const newText = editedText.value.trim()
  // Reflect the edit on the user bubble immediately (optimistic).
  const target = chatStore.messages.find((m) => m.id === editTargetId)
  if (target) target.content = newText
  cancelEdit()
  try {
    // edit_resend rides the typed-action channel (POST /v1/action): the BFF
    // snapshots + discards the turns after this turn, then re-runs the edited
    // question. The composable owns the optimistic discard + stream lifecycle
    // (and the snapshot it leaves behind is restorable via the rail's Undo).
    await editAndResend(editTargetId, newText)
  } catch (err) {
    console.error('[ChatBubble] edit_resend failed', err)
    toast.add({ severity: 'error', summary: t('errors.agentFailed'), life: 3000 })
  }
}

const onEditKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    saveEdit()
  } else if (e.key === 'Escape') {
    cancelEdit()
  }
}
</script>

<template>
  <div class="flex flex-col w-full mb-4" :class="containerClass">
    <div class="flex" :class="containerClass">
      <div class="bubble-shell" :class="[bubbleClass, message?.discarded ? 'discarded' : '']">
        <!-- Phase 4: edit mode — textarea + Save/Cancel -->
        <template v-if="editingMessageId === messageId">
          <Textarea
            v-model="editedText"
            :aria-label="t('chat.edit.placeholder')"
            class="edit-textarea w-full"
            rows="3"
            :max-rows="8"
            autofocus
            autoResize
            @keydown="onEditKeydown"
          />
          <div class="edit-actions">
            <Button
              size="small"
              severity="success"
              :label="t('chat.edit.save')"
              @click="saveEdit"
            />
            <Button
              size="small"
              severity="secondary"
              :label="t('chat.edit.cancel')"
              @click="cancelEdit"
            />
          </div>
        </template>
        <!-- Normal view -->
        <template v-else>
          <component
            :is="renderer"
            :text="renderText"
            :content="envelopeContent(envelope)"
            :details="effectiveDetails"
            :kind="kind"
            :drilldowns="envelope?.drilldowns"
            :display-state="displayState"
            :pending-clarification="showClarification ? envelope?.pendingClarification : null"
            :result-total-rows="envelope?.currentView?.totalRows"
            :message-id="messageId"
            @display-changed="onDisplayChanged"
            @select-row="onSelectRow"
            @show-detail="onShowDetail"
            @sort="onTableSort"
            @filter="onTableFilter"
            @paginate="onTablePaginate"
            @drilldown="onTableDrilldown"
          />
          <!-- Stage clarif-resume 01 — single mount point for the clarification
               UI (D2). Rendered alongside the generic renderer, not inside it,
               so picks route through /v2/chat/resume instead of the chip strip. -->
          <ClarificationCard
            v-if="role === 'assistant' && showClarification"
            :thread-id="sessionId"
            :pending-clarification="envelope!.pendingClarification!"
            @resumed="onResumed"
          />
        </template>
      </div>
    </div>

    <!-- Footer: copy/open-in-tab for assistant; edit pencil for user -->
    <div
      class="bubble-footer"
      :class="role === 'user' ? 'justify-end' : 'justify-start'"
    >
      <!-- User: edit pencil -->
      <Button
        v-if="showPencil"
        text
        size="small"
        icon="pi pi-pencil"
        :aria-label="t('chat.edit.placeholder')"
        class="edit-pencil-btn"
        @click="startEdit"
      />
      <!-- Assistant: agent badge + copy + open-in-tab -->
      <template v-if="showAssistantFooter">
        <AgentBadge
          v-if="showAgentBadge"
          :label="answeringAgent"
          :candidates="reaskCandidates"
          @reask="onReask"
        />
        <Button
          text
          size="small"
          icon="pi pi-copy"
          :aria-label="t('chat.copy')"
          v-tooltip.bottom="t('chat.copy')"
          @click="onCopy"
        />
        <Button
          text
          size="small"
          icon="pi pi-external-link"
          :disabled="!canOpenInTab"
          :aria-label="openInTabTooltip"
          v-tooltip.bottom="openInTabTooltip"
          @click="onOpenInTab"
        />
        <Button
          v-if="canInvestigate"
          text
          size="small"
          icon="pi pi-sparkles"
          class="investigate-btn"
          :aria-label="t('chat.investigate.label')"
          v-tooltip.bottom="t('chat.investigate.tooltip')"
          @click="onInvestigateBlock"
        />
        <Button
          v-if="canPin"
          text
          size="small"
          icon="pi pi-bookmark"
          class="pin-btn"
          :aria-label="t('chat.pin.label')"
          v-tooltip.bottom="t('chat.pin.tooltip')"
          @click="onPin"
        />
        <FeedbackButtons v-if="envelope?.turnId" :turn-id="envelope.turnId" />
      </template>
    </div>

    <!-- Stage 3.2: envelope/v1 Chip oneof (prompt | routing | investigate) -->
    <ChipStrip
      v-if="role === 'assistant' && envelope?.chips && envelope.chips.length > 0"
      :chips="envelope.chips"
      @prompt="onChipPrompt"
      @pick="onRoutingPick"
      @investigate="onInvestigateChip"
    />

    <div
      v-if="props.options && props.options.length > 0"
      class="chips-row"
      :class="role === 'user' ? 'justify-end' : 'justify-start'"
    >
      <Chip
        v-for="(opt, idx) in props.options"
        :key="idx"
        :label="opt"
        class="prompt-chip cursor-pointer"
        @click="emit('option-click', opt)"
      />
    </div>
  </div>
</template>

<style scoped>
.bubble-shell {
  max-width: 80%;
  padding: 0.75rem 1rem;
  border-radius: 1rem;
  font-size: 0.875rem;
  line-height: 1.55;
}
.user-bubble {
  background-color: var(--p-primary-600);
  color: #fff;
  border-bottom-right-radius: 0.25rem;
}
.assistant-bubble {
  background-color: #fff;
  color: var(--p-surface-900);
  border: 1px solid var(--p-surface-200);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  border-bottom-left-radius: 0.25rem;
}
.bubble-footer {
  display: flex;
  gap: 0.25rem;
  margin-top: 0.25rem;
  margin-left: 0.25rem;
}
.bubble-footer :deep(.p-button) {
  width: 1.75rem;
  height: 1.75rem;
}
.chips-row {
  margin-top: 0.5rem;
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}
.prompt-chip {
  font-size: 0.75rem;
  background-color: var(--p-surface-50);
  color: var(--p-surface-700);
  border: 1px solid var(--p-surface-200);
  transition: background-color 150ms ease, color 150ms ease, border-color 150ms ease;
}
.prompt-chip:hover {
  background-color: var(--p-primary-50);
  border-color: var(--p-primary-300);
  color: var(--p-primary-700);
}
.edit-textarea {
  resize: vertical;
  font-size: 0.875rem;
  line-height: 1.55;
  border-radius: 0.75rem;
  padding: 0.5rem 0.75rem;
}
.edit-actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
  justify-content: flex-end;
}
.edit-pencil-btn {
  opacity: 0;
  transition: opacity 150ms ease;
}
.bubble-shell:hover .edit-pencil-btn {
  opacity: 1;
}
.discarded {
  opacity: 0.45;
}
</style>
