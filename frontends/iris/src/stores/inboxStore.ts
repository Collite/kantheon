// Inbox store (Iris Phase 4 Stage 4.1 — PD-2). Holds the aggregated investigation
// view + a live SSE subscription; the header badge and the inbox panel both read
// it. The stream re-emits the whole view on each lifecycle change.
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { inboxApi, type InboxView } from '@/services/inbox'

export const useInboxStore = defineStore('inbox', () => {
  const view = ref<InboxView>({ items: [], counts: { running: 0, needsInput: 0 } })
  let stop: (() => void) | null = null
  // The badge (always mounted) and the panel (mounts/unmounts in the dock) share
  // this singleton store. Ref-count subscribers so closing the panel doesn't tear
  // down the badge's stream — only the 1→0 transition closes the EventStream.
  let subscribers = 0

  const running = computed(() => view.value.counts.running)
  const needsInput = computed(() => view.value.counts.needsInput)
  const hasActivity = computed(() => running.value > 0 || needsInput.value > 0)

  const load = async () => {
    view.value = await inboxApi.view()
  }

  const startStream = () => {
    subscribers += 1
    // Only open on the 0→1 transition; the guard below also no-ops a redundant open.
    if (stop) return
    stop = inboxApi.stream(
      (v) => {
        view.value = v
      },
      // The stream failed to open (non-ok response) or ended without a clean
      // close: fall back to a single poll so a transient failure can't freeze the
      // badge. The stream handle is cleared so a later (re)subscribe can reopen it.
      () => {
        stop = null
        if (subscribers > 0) void load()
      },
    )
  }

  const stopStream = () => {
    if (subscribers === 0) return
    subscribers -= 1
    if (subscribers > 0) return
    stop?.()
    stop = null
  }

  return { view, running, needsInput, hasActivity, load, startStream, stopStream }
})
