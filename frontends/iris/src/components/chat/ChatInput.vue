<script setup lang="ts">
// Chat input + slash command driver (FE-7.2).
//
// Keyboard:
//   - `/` at the start of an empty/leading-slash input opens the popup.
//   - ↑/↓ navigate the popup; Enter selects the highlight; Esc closes.
//   - Enter without a popup-active submits the message normally.
//
// On select: the command is executed via `runSlashCommand` (below). The
// command's `kind` decides whether we clear the input, fire a request,
// or arm a one-shot pre-send hint.
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import SlashCommandPopup from './SlashCommandPopup.vue'
import {
  FORMAT_HINT_KINDS,
  SLASH_COMMANDS,
  filterSlashCommands,
  parseSlashInput,
  type SlashCommandSpec,
} from './slashCommands'
import { useChatStore } from '@/stores/chatStore'
import { useAgentSession } from '@/composables/useAgentSession'
import { usePromptHistory } from '@/composables/usePromptHistory'
import { irisStream } from '@/services/irisStream'
import { FormatEnvelope, FormatKind, formatKindToJSON } from '@/types/envelope'

// User-facing service names accepted by `/refresh <service>`. Empty arg = refresh all (golem
// orchestrates the dependency order). Keep in sync with VALID_SERVICES in golem admin_routes.py.
const REFRESH_SERVICES = ['metadata', 'query-runner', 'fuzzy-matcher', 'golem', 'resolver']

interface Props {
  placeholder: string
  sendLabel: string
  disabled?: boolean
}

defineProps<Props>()
const emit = defineEmits<{
  (e: 'submit'): void
}>()

const session = useAgentSession()
const history = usePromptHistory()
const chatStore = useChatStore()
const toast = useToast()
const confirm = useConfirm()
const { t } = useI18n()

const popupVisible = ref(false)
const highlightedIndex = ref(0)
const inputRef = ref<InstanceType<typeof InputText> | null>(null)

const filteredCommands = computed<SlashCommandSpec[]>(() =>
  filterSlashCommands(session.prompt.value),
)

watch(
  () => session.prompt.value,
  (raw) => {
    if (raw.startsWith('/')) {
      popupVisible.value = true
      const max = filteredCommands.value.length
      if (max === 0) {
        highlightedIndex.value = 0
      } else if (highlightedIndex.value >= max) {
        highlightedIndex.value = max - 1
      }
    } else {
      popupVisible.value = false
      highlightedIndex.value = 0
    }
  },
)

const focusInput = async () => {
  await nextTick()
  // PrimeVue's InputText exposes the underlying <input> via $el.
  const el = (inputRef.value as unknown as { $el?: HTMLInputElement } | null)?.$el
  el?.focus()
}

// -------- slash command execution ---------------------------------------

const runSlashCommand = async (raw: string): Promise<boolean> => {
  // Returns true if a slash command was handled (caller should NOT
  // submit the chat).
  const parsed = parseSlashInput(raw)
  if (!parsed) return false
  const { spec, arg } = parsed
  if (!spec) {
    // User typed `/something` that isn't a command — leave it to fall
    // through as a normal message. Some users genuinely start a
    // sentence with `/`.
    return false
  }

  popupVisible.value = false

  switch (spec.name) {
    case 'clear':
      confirm.require({
        message: t('slash.clearConfirmMessage'),
        header: t('slash.clearConfirmHeader'),
        icon: 'pi pi-exclamation-triangle',
        rejectProps: { label: t('slash.clearConfirmReject'), severity: 'secondary', text: true },
        acceptProps: { label: t('slash.clearConfirmAccept') },
        accept: () => {
          chatStore.clear()
          // Re-seed welcome from the current language.
          session.changeLanguage(session.selectedLang.value)
          toast.add({ severity: 'success', summary: t('slash.chatCleared'), life: 1500 })
        },
      })
      session.prompt.value = ''
      return true

    case 'help': {
      // Iterate the full registry — `filteredCommands` is narrowed by the
      // current input ("/help") and would only show "help" itself.
      const lines = [
        `**${t('slash.headerListTitle')}**`,
        '',
        `| ${t('slash.headerCommand')} | ${t('slash.headerDescription')} |`,
        '| --- | --- |',
        ...SLASH_COMMANDS.map(
          (c) =>
            `| \`/${c.name}${c.argHint ? ' ' + c.argHint : ''}\` | ${t(c.descriptionKey)} |`,
        ),
      ]
      // System bubble: append a synthesised assistant message with a
      // markdown envelope.
      chatStore.messages.push({
        id: `help-${Date.now().toString(36)}`,
        role: 'assistant',
        content: lines.join('\n'),
        timestamp: new Date(),
        envelope: FormatEnvelope.create({
          bubbleId: `help_${Date.now().toString(36)}`,
          turnId: `help_${Date.now().toString(36)}`,
          threadId: session.sessionId.value,
          text: lines.join('\n'),
          format: { kind: FormatKind.MARKDOWN },
          createdAt: new Date().toISOString(),
          agentVersion: session.agentVersion.value ?? 'golem-v2.0.0',
        }),
      })
      session.prompt.value = ''
      return true
    }

    case 'new':
      try {
        await session.startNewSession()
        toast.add({ severity: 'success', summary: t('slash.newSession'), life: 1500 })
      } catch (err) {
        console.warn('new session failed', err)
        toast.add({
          severity: 'error',
          summary: t('slash.newSessionFailed'),
          detail: (err as Error).message ?? 'unknown error',
          life: 3000,
        })
      }
      session.prompt.value = ''
      return true

    case 'reset':
      // Server-side clear: snapshots + discards turns; the rail surfaces an Undo
      // while the snapshot is restorable. /clear (above) is FE-only by contrast.
      session.prompt.value = ''
      try {
        await session.resetCurrentSession()
        toast.add({ severity: 'success', summary: t('slash.resetDone'), life: 2000 })
      } catch (err) {
        console.warn('reset failed', err)
        toast.add({
          severity: 'error',
          summary: t('slash.resetFailed'),
          detail: (err as Error).message ?? 'unknown error',
          life: 3000,
        })
      }
      return true

    case 'refresh': {
      const svc = (arg ?? '').trim().toLowerCase()
      if (svc && !REFRESH_SERVICES.includes(svc)) {
        toast.add({
          severity: 'warn',
          summary: t('slash.refreshUsage'),
          detail: t('slash.refreshUsageDetail', { services: REFRESH_SERVICES.join(' | ') }),
          life: 3500,
        })
        return true
      }
      session.prompt.value = ''
      const target = svc || t('slash.refreshAll')
      toast.add({ severity: 'info', summary: t('slash.refreshStarted', { target }), life: 1500 })
      try {
        const res = await irisStream.refresh(svc || undefined)
        const lines = [
          `**${t('slash.refreshDoneTitle', { target })}**`,
          '',
          `| ${t('slash.refreshColService')} | ${t('slash.refreshColStatus')} | ${t('slash.refreshColDetail')} |`,
          '| --- | --- | --- |',
          ...res.results.map(
            (r) => `| \`${r.service}\` | ${r.status} | ${r.version ?? r.detail ?? ''} |`,
          ),
        ]
        const stamp = Date.now().toString(36)
        chatStore.messages.push({
          id: `refresh-${stamp}`,
          role: 'assistant',
          content: lines.join('\n'),
          timestamp: new Date(),
          envelope: FormatEnvelope.create({
            bubbleId: `refresh_${stamp}`,
            turnId: `refresh_${stamp}`,
            threadId: session.sessionId.value,
            text: lines.join('\n'),
            format: { kind: FormatKind.MARKDOWN },
            createdAt: new Date().toISOString(),
            agentVersion: session.agentVersion.value ?? 'golem-v2.0.0',
          }),
        })
        const anyFailed = res.results.some((r) => r.status === 'failed')
        toast.add({
          severity: anyFailed ? 'warn' : 'success',
          summary: anyFailed ? t('slash.refreshPartial') : t('slash.refreshDone'),
          life: 2000,
        })
      } catch (err) {
        console.warn('refresh failed', err)
        toast.add({
          severity: 'error',
          summary: t('slash.refreshFailed'),
          detail: (err as Error).message ?? 'unknown error',
          life: 3500,
        })
      }
      return true
    }

    case 'format': {
      const kind = (arg ?? '').toLowerCase()
      if (!arg || !FORMAT_HINT_KINDS.includes(kind)) {
        toast.add({
          severity: 'warn',
          summary: t('slash.formatUsage'),
          detail: t('slash.formatUsageDetail', { kinds: FORMAT_HINT_KINDS.join(' | ') }),
          life: 3000,
        })
        return true
      }
      session.desiredFormat.value = kind
      session.prompt.value = ''
      toast.add({
        severity: 'info',
        summary: t('slash.formatHint', { kind }),
        life: 1500,
      })
      return true
    }

    case 'export':
      handleExport(arg)
      session.prompt.value = ''
      return true

    case 'sql':
      // Stage 2.3 slash audit: the v1 turn request carries no `dryRun` field, so
      // arming `dryRunNext` was a silent no-op. Surface that it's not wired yet
      // (the field returns with the typed-action surface in Phase 3) rather than
      // pretend the next turn will dry-run.
      session.prompt.value = ''
      toast.add({
        severity: 'warn',
        summary: t('slash.sqlUnavailable'),
        life: 2500,
      })
      return true
  }
  return false
}

// -------- /export <md|csv> ----------------------------------------------

const exportFormatFor = (msg: { envelope?: FormatEnvelope }): string =>
  msg.envelope?.format ? formatKindToJSON(msg.envelope.format.kind).toLowerCase() : 'plaintext'

const downloadBlob = (text: string, filename: string, mime: string) => {
  const blob = new Blob(['﻿' + text], { type: `${mime};charset=utf-8` })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(a.href)
}

const handleExport = (arg: string | undefined) => {
  const variant = (arg ?? 'md').toLowerCase()
  if (variant !== 'md' && variant !== 'csv') {
    toast.add({
      severity: 'warn',
      summary: t('slash.exportUsage'),
      life: 2500,
    })
    return
  }

  if (chatStore.messages.length === 0) {
    toast.add({ severity: 'info', summary: t('slash.exportNothing'), life: 1500 })
    return
  }

  const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')

  if (variant === 'md') {
    const lines: string[] = ['# Chat export', `_Exported ${new Date().toISOString()}_`, '']
    for (const msg of chatStore.messages) {
      const role = msg.role === 'user' ? '**You**' : '**Agent**'
      const kind = exportFormatFor(msg)
      const time = msg.timestamp.toISOString()
      lines.push(`### ${role} · ${time} · _${kind}_`, '')
      lines.push(msg.envelope?.text ?? msg.content ?? '', '')
    }
    downloadBlob(lines.join('\n'), `chat-${stamp}.md`, 'text/markdown')
    toast.add({ severity: 'success', summary: t('slash.markdownExported'), life: 1500 })
    return
  }

  // CSV: role,time,format,text
  const csvCell = (value: string) => {
    if (/[",\n\r]/.test(value)) return `"${value.replace(/"/g, '""')}"`
    return value
  }
  const rows: string[] = ['role,time,format,text']
  for (const msg of chatStore.messages) {
    rows.push(
      [
        csvCell(msg.role),
        csvCell(msg.timestamp.toISOString()),
        csvCell(exportFormatFor(msg)),
        csvCell(msg.envelope?.text ?? msg.content ?? ''),
      ].join(','),
    )
  }
  downloadBlob(rows.join('\n'), `chat-${stamp}.csv`, 'text/csv')
  toast.add({ severity: 'success', summary: t('slash.csvExported'), life: 1500 })
}

// -------- submit handlers ------------------------------------------------

const onSubmit = async () => {
  const raw = session.prompt.value.trim()
  if (!raw) return
  if (raw.startsWith('/')) {
    const handled = await runSlashCommand(raw)
    if (handled) return
  }
  history.record(raw) // only real questions reach here — slash commands returned above
  emit('submit')
}

const onKeydown = async (event: KeyboardEvent) => {
  if (popupVisible.value && filteredCommands.value.length > 0) {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      highlightedIndex.value =
        (highlightedIndex.value + 1) % filteredCommands.value.length
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      const len = filteredCommands.value.length
      highlightedIndex.value = (highlightedIndex.value - 1 + len) % len
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      popupVisible.value = false
      return
    }
    if (event.key === 'Enter') {
      // Enter while popup is open + a partial input matches a command:
      // run the highlighted command immediately. If the user has typed
      // a complete `/cmd …`, fall through to onSubmit which parses it.
      const raw = session.prompt.value.trim()
      const parsed = parseSlashInput(raw)
      if (parsed?.spec) {
        // Already a complete command → let onSubmit handle it.
        return
      }
      const spec = filteredCommands.value[highlightedIndex.value]
      if (spec) {
        event.preventDefault()
        onSelect(spec)
      }
      return
    }
  }

  // History recall — only when the slash popup is closed, so it never collides
  // with the popup's own ↑/↓ navigation above.
  if (!popupVisible.value) {
    if (event.key === 'ArrowUp') {
      const next = history.arrowUp(session.prompt.value)
      if (next !== null) {
        event.preventDefault()
        session.prompt.value = next
        return
      }
    } else if (event.key === 'ArrowDown') {
      const next = history.arrowDown(session.prompt.value)
      if (next !== null) {
        event.preventDefault()
        session.prompt.value = next
        return
      }
    } else if (event.key.length === 1 || event.key === 'Backspace' || event.key === 'Delete') {
      // User is editing → leave history navigation (re-arms the empty rule).
      history.reset()
    }
  }
}

const onSelect = (spec: SlashCommandSpec) => {
  // For commands that take an arg, populate `/<name> ` and keep the popup
  // open so the user can type the arg. For arg-less commands, run
  // immediately.
  if (spec.acceptsArg) {
    session.prompt.value = `/${spec.name} `
    popupVisible.value = false
    void focusInput()
    return
  }
  session.prompt.value = `/${spec.name}`
  popupVisible.value = false
  void runSlashCommand(`/${spec.name}`)
  void focusInput()
}

// -------- pre-send hint indicator ---------------------------------------

const hintTag = computed(() => {
  if (session.dryRunNext.value)
    return { label: t('slash.hintTagSql'), icon: 'pi pi-database' }
  if (session.desiredFormat.value)
    return {
      label: t('slash.hintTagFormat', { kind: session.desiredFormat.value }),
      icon: 'pi pi-bullseye',
    }
  return null
})

const clearHint = () => {
  session.dryRunNext.value = false
  session.desiredFormat.value = null
}

// -------- row-detail selection indicator --------------------------------

// When the user picked "Show detail" on a table row, show a chip so they can
// see that "this" is bound to a selection — with an × to drop it.
const selectionArmed = computed(() => session.armedSelection.value !== null)
const clearSelection = () => session.clearSelection()
</script>

<template>
  <div class="chat-input-wrap">
    <div v-if="hintTag" class="hint-row">
      <Tag severity="info" :icon="hintTag.icon">
        {{ hintTag.label }}
        <button
          type="button"
          class="hint-clear"
          :aria-label="t('slash.hintCancelAria')"
          @click="clearHint"
        >×</button>
      </Tag>
    </div>

    <div v-if="selectionArmed" class="hint-row">
      <Tag severity="success" icon="pi pi-search-plus" class="selection-tag">
        {{ t('detail.chip') }}
        <button
          type="button"
          class="hint-clear"
          :aria-label="t('detail.chipClearAria')"
          @click="clearSelection"
        >×</button>
      </Tag>
    </div>

    <div class="chat-input-anchor">
      <SlashCommandPopup
        :commands="filteredCommands"
        :visible="popupVisible"
        :highlighted-index="highlightedIndex"
        @update:highlighted-index="(i) => (highlightedIndex = i)"
        @select="onSelect"
      />

      <form @submit.prevent="onSubmit" class="flex gap-3">
        <InputText
          id="chat-input"
          ref="inputRef"
          v-model="session.prompt.value"
          :placeholder="placeholder"
          :disabled="disabled"
          autocomplete="off"
          class="flex-1"
          @keydown="onKeydown"
        />
        <Button
          type="submit"
          :loading="disabled"
          :disabled="!session.prompt.value.trim() || disabled"
          icon="pi pi-send"
          :label="sendLabel"
          :aria-label="sendLabel"
        />
      </form>
    </div>
  </div>
</template>

<style scoped>
.chat-input-wrap {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.hint-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.hint-clear {
  margin-left: 0.4rem;
  background: transparent;
  border: none;
  color: inherit;
  cursor: pointer;
  font-size: 1rem;
  line-height: 1;
  padding: 0 0.15rem;
}
.hint-clear:hover {
  opacity: 0.7;
}
.chat-input-anchor {
  position: relative;
}
</style>
