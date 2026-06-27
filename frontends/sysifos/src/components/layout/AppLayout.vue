<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()

// Nav model (§8 screens). Audit is admin-only; the rest are open to any
// authenticated user (finer roles are a v1.x concern, plan §6).
const items = computed(() =>
  [
    { label: 'Clients', to: '/clients' },
    { label: 'Portfolios', to: '/portfolios' },
    { label: 'Assets', to: '/assets' },
    { label: 'Transactions', to: '/transactions' },
    { label: 'Balance entry', to: '/balance-entry' },
    { label: 'Import', to: '/import' },
    { label: 'Reconcile', to: '/reconcile' },
    { label: 'Loaders', to: '/loaders' },
    { label: 'Audit', to: '/audit', admin: true },
  ].filter((i) => !i.admin || session.hasRole('midas:admin')),
)

function onSwitchTenant(e: Event) {
  session.switchTenant((e.target as HTMLSelectElement).value)
}
</script>

<template>
  <div class="app-shell">
    <header class="app-bar">
      <span class="brand">Sysifos</span>
      <span class="spacer" />
      <label v-if="session.hasMultipleTenants" class="tenant-switch">
        Tenant:
        <select :value="session.tenantId" @change="onSwitchTenant">
          <option v-for="t in session.tenants" :key="t" :value="t">{{ t }}</option>
        </select>
      </label>
      <span v-else class="tenant">{{ session.tenantId }}</span>
      <span class="user">{{ session.userId }}</span>
    </header>
    <div class="app-body">
      <nav class="app-nav">
        <RouterLink v-for="item in items" :key="item.to" :to="item.to" class="nav-link">
          {{ item.label }}
        </RouterLink>
      </nav>
      <main class="app-content">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.app-bar {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
}
.brand {
  font-weight: 700;
}
.spacer {
  flex: 1;
}
.app-body {
  display: flex;
  flex: 1;
  min-height: 0;
}
.app-nav {
  display: flex;
  flex-direction: column;
  width: 12rem;
  padding: 1rem 0.5rem;
  gap: 0.25rem;
  border-right: 1px solid var(--p-content-border-color, #e5e7eb);
}
.nav-link {
  padding: 0.4rem 0.75rem;
  border-radius: 6px;
  text-decoration: none;
  color: inherit;
}
.nav-link.router-link-active {
  background: var(--p-primary-color, #6366f1);
  color: #fff;
}
.app-content {
  flex: 1;
  padding: 1.5rem;
  overflow: auto;
}
</style>
