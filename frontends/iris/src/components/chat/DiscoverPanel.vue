<script setup lang="ts">
// Iris Phase 4 Stage 4.3 — discovery surface (PD-7). Shown on a fresh/empty
// session: role-filtered domain cards ("what can I ask about ERP, HR,
// Investment…") with example-question chips. Clicking a chip submits it as a
// normal turn.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Chip from 'primevue/chip'
import { discoverApi, type DomainCard } from '@/services/discover'

const emit = defineEmits<{
  (e: 'ask', question: string): void
}>()

const { t } = useI18n()
const domains = ref<DomainCard[]>([])

onMounted(async () => {
  domains.value = await discoverApi.domains()
})
</script>

<template>
  <div v-if="domains.length > 0" class="discover">
    <h3 class="discover-title">{{ t('discover.title') }}</h3>
    <p class="discover-sub">{{ t('discover.subtitle') }}</p>
    <div class="discover-cards">
      <section v-for="d in domains" :key="d.agentId" class="discover-card">
        <header class="discover-card-head">
          <span class="discover-card-name">{{ d.displayName }}</span>
        </header>
        <p v-if="d.blurb" class="discover-card-blurb">{{ d.blurb }}</p>
        <div
          v-if="d.exampleQuestions.length > 0"
          class="discover-examples"
          :aria-label="t('discover.examplesAria', { domain: d.displayName })"
        >
          <Chip
            v-for="(q, i) in d.exampleQuestions"
            :key="i"
            :label="q"
            class="discover-example cursor-pointer"
            tabindex="0"
            @click="emit('ask', q)"
            @keydown.enter="emit('ask', q)"
            @keydown.space.prevent="emit('ask', q)"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.discover {
  margin: 0 auto 1.5rem auto;
  max-width: 52rem;
  width: 100%;
}
.discover-title {
  font-size: 1.05rem;
  font-weight: 600;
  margin: 0 0 0.2rem 0;
}
.discover-sub {
  font-size: 0.82rem;
  color: var(--p-surface-500);
  margin: 0 0 0.9rem 0;
}
.discover-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
  gap: 0.75rem;
}
.discover-card {
  border: 1px solid var(--p-surface-200);
  border-radius: 0.75rem;
  background: #fff;
  padding: 0.7rem 0.85rem;
}
.discover-card-name {
  font-weight: 600;
  font-size: 0.9rem;
}
.discover-card-blurb {
  font-size: 0.78rem;
  color: var(--p-surface-600);
  margin: 0.25rem 0 0.55rem 0;
}
.discover-examples {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  align-items: flex-start;
}
.discover-example {
  font-size: 0.74rem;
  background-color: var(--p-surface-50);
  color: var(--p-surface-700);
  border: 1px solid var(--p-surface-200);
}
.discover-example:hover {
  background-color: var(--p-primary-50);
  border-color: var(--p-primary-300);
  color: var(--p-primary-700);
}
</style>
