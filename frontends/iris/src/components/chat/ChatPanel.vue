<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Skeleton from 'primevue/skeleton'
import ChatBubble from './ChatBubble.vue'
import ChatInput from './ChatInput.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import DiscoverPanel from './DiscoverPanel.vue'
import ServerRestartBanner from '@/components/layout/ServerRestartBanner.vue'
import { useAgentSession } from '@/composables/useAgentSession'

const session = useAgentSession()
const { messages, streaming } = storeToRefs(session.chatStore)
const { t } = useI18n()

const tInput = computed(() => {
  // Welcome-line copy still uses the per-language constant in
  // useAgentSession (covers en/de/cs/sk/hu); chat-input strings come
  // from vue-i18n.
  const placeholder = t('agent.placeholder')
  const send = t('agent.send')
  return { placeholder, send }
})

const chatContainer = ref<HTMLElement | null>(null)

// FE-8.5: stick-to-bottom unless the user has scrolled up. `userPinnedUp`
// flips on a real upward scroll and back off when the user comes within
// `STICK_THRESHOLD_PX` of the bottom (or hits "scroll to latest"). New
// messages don't auto-scroll while pinned up — that's what makes the
// affordance useful.
const STICK_THRESHOLD_PX = 64
const userPinnedUp = ref(false)
let suppressNextScrollEvent = false

const isAtBottom = () => {
  const el = chatContainer.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_THRESHOLD_PX
}

const scrollToBottom = async (smooth = false) => {
  await nextTick()
  const el = chatContainer.value
  if (!el) return
  suppressNextScrollEvent = true
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  // The smooth scroll fires multiple scroll events; clear the flag on
  // the next animation frame so the pin-up detection re-arms.
  requestAnimationFrame(() => {
    suppressNextScrollEvent = false
  })
}

const onScroll = () => {
  if (suppressNextScrollEvent) return
  userPinnedUp.value = !isAtBottom()
}

const onScrollToLatest = () => {
  userPinnedUp.value = false
  void scrollToBottom(true)
}

watch(
  () => messages.value.length,
  () => {
    if (!userPinnedUp.value) void scrollToBottom()
  },
)
watch(
  () => messages.value[messages.value.length - 1]?.content,
  () => {
    if (!userPinnedUp.value) void scrollToBottom()
  },
)

onMounted(() => {
  // Anchor at the bottom when the panel mounts so the welcome bubble
  // sits at the bottom with empty space above (history-from-bottom).
  void scrollToBottom()
  chatContainer.value?.addEventListener('scroll', onScroll, { passive: true })
})

onBeforeUnmount(() => {
  chatContainer.value?.removeEventListener('scroll', onScroll)
})

const onSubmit = async () => {
  userPinnedUp.value = false
  await session.sendMessage()
  void scrollToBottom()
}

// FE-8.6: skeleton while `format_response` is running. The format-node
// chooses the kind asynchronously, so we don't know whether the next
// bubble will be plaintext / markdown / table / chart yet — a generic
// table-shaped skeleton covers the typical case and reads as
// "structured content arriving" for the others.
const showFormatSkeleton = computed(() => {
  if (!streaming.value) return false
  if (session.currentNode.value !== 'format_response') return false
  const last = messages.value[messages.value.length - 1]
  return last?.role === 'assistant' && !last.envelope
})

const lastMessageEmpty = computed(() => {
  const last = messages.value[messages.value.length - 1]
  return last?.role === 'assistant' && last.content.length === 0 && !last.envelope
})

// Stage 4.3 — discovery panel on a fresh session (only the welcome bubble).
const showDiscover = computed(() => {
  if (streaming.value) return false
  const msgs = messages.value
  return msgs.length === 0 || (msgs.length === 1 && msgs[0]?.id === 'welcome')
})

const onAsk = async (question: string) => {
  session.prompt.value = question
  userPinnedUp.value = false
  await session.sendMessage()
  void scrollToBottom()
}
</script>

<template>
  <div class="chat-panel flex flex-col h-full min-h-0 relative">
    <ServerRestartBanner />

    <div
      ref="chatContainer"
      class="chat-history flex-1 p-6 overflow-y-auto scroll-smooth"
    >
      <!-- FE-8.5: history-from-bottom. The inner column flexes to its
           content height; auto-margin-top pushes a short conversation
           down so the welcome bubble sits at the bottom. As messages
           accumulate beyond the viewport, vertical scroll kicks in
           naturally. -->
      <div class="chat-history-inner">
        <ChatBubble
          v-for="msg in messages"
          :key="msg.id"
          :role="msg.role"
          :content="msg.content"
          :options="msg.options"
          :envelope="msg.envelope"
          :timestamp="msg.timestamp"
          :message-id="msg.id"
          :display-state="msg.displayState"
          @option-click="session.handleOptionClick"
        />

        <DiscoverPanel v-if="showDiscover" @ask="onAsk" />

        <div v-if="showFormatSkeleton" class="format-skeleton" aria-hidden="true">
          <Skeleton class="skel-row skel-header" />
          <Skeleton class="skel-row" />
          <Skeleton class="skel-row" />
          <Skeleton class="skel-row" />
        </div>

        <ThinkingIndicator
          v-else-if="streaming && lastMessageEmpty"
        />
        <div
          v-if="session.agentStatus.value"
          class="text-xs italic mt-2 ml-14"
          style="color: var(--p-surface-500);"
        >
          {{ session.agentStatus.value }}
        </div>
      </div>
    </div>

    <Button
      v-if="userPinnedUp"
      class="scroll-to-latest"
      rounded
      raised
      icon="pi pi-arrow-down"
      :aria-label="t('agent.scrollToLatest')"
      v-tooltip.left="t('agent.scrollToLatest')"
      @click="onScrollToLatest"
    />

    <div
      class="chat-input-row p-4 border-t bg-white"
      style="border-color: var(--p-surface-200);"
    >
      <ChatInput
        :placeholder="tInput.placeholder"
        :send-label="tInput.send"
        :disabled="streaming"
        @submit="onSubmit"
      />
    </div>
  </div>
</template>

<style scoped>
.chat-history {
  background-color: color-mix(in srgb, var(--p-surface-50) 70%, transparent);
  /* Stack messages from the bottom — short conversations sit at the
   * bottom with empty space above, longer ones scroll naturally. */
  display: flex;
  flex-direction: column;
}
.chat-history-inner {
  margin-top: auto;
  display: flex;
  flex-direction: column;
}

.scroll-to-latest {
  position: absolute;
  right: 1.25rem;
  bottom: 5rem;
  z-index: 5;
}

.format-skeleton {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-bottom: 1rem;
  max-width: 80%;
  padding: 0.75rem 1rem;
  background-color: #fff;
  border: 1px solid var(--p-surface-200);
  border-radius: 1rem;
  border-bottom-left-radius: 0.25rem;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.format-skeleton :deep(.skel-row) {
  height: 0.95rem;
  border-radius: 0.4rem;
}
.format-skeleton :deep(.skel-header) {
  height: 1.1rem;
  width: 40%;
  background: var(--p-surface-200);
}
</style>
