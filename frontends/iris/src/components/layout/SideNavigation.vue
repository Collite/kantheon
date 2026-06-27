<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import { useToast } from 'primevue/usetoast'
import { useAuthStore } from '@/stores/auth'
import { useLayoutStore } from '@/stores/layoutStore'
import { clearPersistedLayout } from '@/stores/layoutPersistence'
import {
  BOTTOM_PANES,
  type BottomPane,
  type BottomPaneId,
} from '@/components/layout/bottomPaneRegistry'
import InboxBadge from '@/components/inbox/InboxBadge.vue'
import { config } from '@/config'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const layoutStore = useLayoutStore()
const toast = useToast()
const { t } = useI18n()

const userName = computed(() => {
  if (authStore.user) return authStore.user.name || authStore.user.preferred_username || 'User'
  if (authStore.fallbackUserName) return authStore.fallbackUserName
  return t('nav.user.loading')
})

const userInitials = computed(() => {
  if (authStore.user) {
    const name = authStore.user.name || authStore.user.preferred_username || 'U'
    return name.substring(0, 2).toUpperCase()
  }
  if (authStore.fallbackUserName) return authStore.fallbackUserName.substring(0, 2).toUpperCase()
  return '?'
})

interface NavItem {
  href: string
  icon: string
  // Either an i18n key (resolved via `t()` at render time, so the language
  // switcher reflects live) or a literal label already in the user's language.
  // Agent labels come from runtime config (`VITE_GOLEM_AGENTS`), not i18n.
  nameKey?: string
  ariaKey?: string
  label?: string
  aria?: string
}

// Static tool entries below the Agents submenu. Computed so the language
// switcher reflects live. The Inspector + LLM Gateway entries were unwired in
// the Iris Phase 2 Stage 2.2 BFF re-point (views kept on disk, off the nav).
const navigation = computed<NavItem[]>(() => [])

const navLabel = (item: NavItem) => (item.nameKey ? t(item.nameKey) : (item.label ?? ''))
const navAria = (item: NavItem) =>
  item.ariaKey ? t(item.ariaKey) : (item.aria ?? navLabel(item))

const isActive = (href: string) => route.path === href

const navigate = (href: string) => {
  router.push(href)
}

// ── Agents submenu ──────────────────────────────────────────────────────
// The configured Golem agents (from VITE_GOLEM_AGENTS, or the single-agent
// fallback) shown nested under a top-level "Agents" group.
interface AgentNavItem {
  id: string
  label: string
  href: string
}
const agentItems = computed<AgentNavItem[]>(() =>
  config.golemAgents.map((agent) => ({
    id: agent.id,
    label: agent.label,
    href: `/agents/${agent.id}`,
  })),
)
const isAnyAgentActive = computed(() => agentItems.value.some((a) => isActive(a.href)))

// Expanded by default. Auto-opens whenever an agent route becomes active, but
// the user can still toggle it shut.
const agentsExpanded = ref(true)
const toggleAgents = () => {
  agentsExpanded.value = !agentsExpanded.value
}
watch(isAnyAgentActive, (active) => {
  if (active) agentsExpanded.value = true
})

// Collapsed-rail flyout — when the sidebar is icon-only there's no room to nest,
// so the agent list opens as a popup anchored to the Agents icon (mirrors the
// View menu pattern below).
const agentsMenuRef = ref<InstanceType<typeof Menu> | null>(null)
const openAgentsMenu = (event: MouseEvent) => agentsMenuRef.value?.toggle(event)
const agentsMenuItems = computed(() => agentItems.value.map((agent) => ({ extra: { agent } })))

const onAgentsHeaderClick = (event: MouseEvent) => {
  if (collapsed.value) openAgentsMenu(event)
  else toggleAgents()
}

// ── Collapse toggle ─────────────────────────────────────────────────────
const collapsed = computed(() => layoutStore.sideNavCollapsed)
const toggleCollapsed = () => layoutStore.toggleSideNavCollapsed()

// ── View menu ───────────────────────────────────────────────────────────
// PrimeVue's <Menu> in popup mode: anchored to a Button via `toggle($event)`.
// Items rendered through the `#item` slot so we can show a check / blank
// glyph next to each pane name.
const viewMenuRef = ref<InstanceType<typeof Menu> | null>(null)
const openViewMenu = (event: MouseEvent) => viewMenuRef.value?.toggle(event)

const onResetLayout = () => {
  layoutStore.resetLayout()
  clearPersistedLayout(authStore.userId)
  toast.add({
    severity: 'success',
    summary: t('layout.resetToastSummary'),
    detail: t('layout.resetToastDetail'),
    life: 2500,
  })
}

interface PaneMenuItem {
  kind: 'pane'
  pane: BottomPane
  visible: boolean
}
interface DividerItem { kind: 'divider' }
interface ActionItem {
  kind: 'action'
  label: string
  icon: string
  command: () => void
}
type ViewMenuItem = PaneMenuItem | DividerItem | ActionItem

// PrimeVue's Menu items are typed as `MenuItem[]`. We attach our own data
// under the `extra` key and render via the `#item` slot, ignoring most of
// the default item structure.
const viewMenuItems = computed(() => {
  const items: Array<{ extra: ViewMenuItem; separator?: boolean }> = []
  for (const pane of BOTTOM_PANES) {
    items.push({
      extra: {
        kind: 'pane',
        pane,
        visible: layoutStore.isPaneVisible(pane.id),
      },
    })
  }
  items.push({ separator: true, extra: { kind: 'divider' } })
  items.push({
    extra: {
      kind: 'action',
      label: t('layout.reset'),
      icon: 'pi pi-refresh',
      command: onResetLayout,
    },
  })
  return items
})

const onPaneItemClick = (id: BottomPaneId) => {
  layoutStore.togglePane(id)
}
</script>

<template>
  <div
    class="side-nav flex h-full flex-col text-white shadow-xl"
    :class="{ 'side-nav--collapsed': collapsed }"
    style="background-color: var(--p-surface-900);"
  >
    <!-- Brand row + collapse toggle. When collapsed, the brand text hides
         and the toggle button stays visible. -->
    <div
      class="flex h-16 items-center border-b"
      :class="collapsed ? 'justify-center px-2' : 'justify-between px-4'"
      style="border-color: var(--p-surface-800);"
    >
      <div v-if="!collapsed" class="flex items-center gap-3 min-w-0">
        <div
          class="h-8 w-8 rounded-lg flex items-center justify-center shrink-0"
          style="background-color: var(--p-primary-600);"
        >
          <i class="pi pi-bolt text-white" style="font-size: 1.05rem;"></i>
        </div>
        <span class="text-lg font-bold tracking-tight truncate">{{ t('nav.brand') }}</span>
        <InboxBadge />
      </div>
      <Button
        text
        plain
        rounded
        class="collapse-toggle"
        :aria-label="collapsed ? t('nav.expandAria') : t('nav.collapseAria')"
        :aria-expanded="!collapsed"
        v-tooltip.right="collapsed ? t('nav.expand') : t('nav.collapse')"
        @click="toggleCollapsed"
      >
        <i
          :class="collapsed ? 'pi pi-chevron-right' : 'pi pi-chevron-left'"
          aria-hidden="true"
        ></i>
      </Button>
    </div>

    <!-- Primary navigation -->
    <nav
      class="flex-1 py-6 space-y-1 overflow-y-auto overflow-x-hidden"
      :class="collapsed ? 'px-2' : 'px-3'"
      :aria-label="t('nav.primaryAria')"
    >
      <!-- Agents submenu — a top-level "Agents" group with the configured
           agents nested beneath. Expanded sidebar: inline accordion. Collapsed
           rail: a popup flyout anchored to the icon. -->
      <div class="agents-group">
        <Button
          text
          plain
          class="nav-item w-full"
          :class="{ 'nav-item--active': isAnyAgentActive, 'nav-item--collapsed': collapsed }"
          :aria-label="collapsed ? t('nav.items.agentsAria') : t('nav.items.agentsToggleAria')"
          :aria-expanded="collapsed ? undefined : agentsExpanded"
          aria-haspopup="true"
          v-tooltip.right="collapsed ? t('nav.items.agents') : undefined"
          @click="onAgentsHeaderClick"
        >
          <i :class="['pi pi-users', collapsed ? '' : 'mr-3']" aria-hidden="true"></i>
          <span v-if="!collapsed" class="text-sm font-medium flex-1 text-left">{{ t('nav.items.agents') }}</span>
          <i
            v-if="!collapsed"
            class="agents-chevron"
            :class="agentsExpanded ? 'pi pi-chevron-down' : 'pi pi-chevron-right'"
            aria-hidden="true"
          ></i>
        </Button>

        <!-- Nested agent items (expanded sidebar only) -->
        <div v-if="!collapsed && agentsExpanded" class="agents-children" role="group">
          <Button
            v-for="agent in agentItems"
            :key="agent.id"
            text
            plain
            class="nav-item nav-subitem w-full"
            :class="{ 'nav-item--active': isActive(agent.href) }"
            :aria-label="agent.label"
            :aria-current="isActive(agent.href) ? 'page' : undefined"
            @click="navigate(agent.href)"
          >
            <i class="pi pi-comments mr-3" aria-hidden="true"></i>
            <span class="text-sm font-medium">{{ agent.label }}</span>
          </Button>
        </div>

        <!-- Collapsed-rail flyout -->
        <Menu
          ref="agentsMenuRef"
          :model="agentsMenuItems"
          :popup="true"
          append-to="body"
        >
          <template #item="{ item, props }">
            <a
              v-bind="props.action"
              role="menuitem"
              @click="navigate((item as any).extra.agent.href)"
            >
              <i class="pi pi-comments view-menu-pane-icon" aria-hidden="true"></i>
              <span class="view-menu-label">{{ (item as any).extra.agent.label }}</span>
            </a>
          </template>
        </Menu>
      </div>

      <Button
        v-for="item in navigation"
        :key="item.href"
        text
        plain
        class="nav-item w-full"
        :class="{ 'nav-item--active': isActive(item.href), 'nav-item--collapsed': collapsed }"
        :aria-label="navAria(item)"
        :aria-current="isActive(item.href) ? 'page' : undefined"
        v-tooltip.right="collapsed ? navLabel(item) : undefined"
        @click="navigate(item.href)"
      >
        <i :class="[item.icon, collapsed ? '' : 'mr-3']" aria-hidden="true"></i>
        <span v-if="!collapsed" class="text-sm font-medium">{{ navLabel(item) }}</span>
      </Button>
    </nav>

    <!-- View menu (above user info, per spec-fe-2.1 §4.1 R-NAV-5) -->
    <div
      class="view-menu-wrap"
      :class="collapsed ? 'px-2' : 'px-3'"
    >
      <Button
        text
        plain
        class="nav-item w-full"
        :class="{ 'nav-item--collapsed': collapsed }"
        :aria-label="t('nav.viewAria')"
        aria-haspopup="true"
        v-tooltip.right="collapsed ? t('nav.view') : undefined"
        @click="openViewMenu"
      >
        <i :class="['pi pi-eye', collapsed ? '' : 'mr-3']" aria-hidden="true"></i>
        <span v-if="!collapsed" class="text-sm font-medium">{{ t('nav.view') }}</span>
      </Button>

      <!-- Popup menu — anchored to the Button above. Items rendered via the
           #item slot so we can show check / blank glyphs against each pane.
           PrimeVue handles outside-click + ESC dismissal. -->
      <Menu
        ref="viewMenuRef"
        :model="viewMenuItems"
        :popup="true"
        append-to="body"
      >
        <template #item="{ item, props }">
          <template v-if="(item as any).extra?.kind === 'pane'">
            <a
              v-bind="props.action"
              role="menuitemcheckbox"
              :aria-checked="(item as any).extra.visible"
              @click="onPaneItemClick((item as any).extra.pane.id)"
            >
              <i
                class="view-menu-check"
                :class="(item as any).extra.visible ? 'pi pi-check' : ''"
                aria-hidden="true"
              ></i>
              <i
                :class="(item as any).extra.pane.icon"
                class="view-menu-pane-icon"
                aria-hidden="true"
              ></i>
              <span class="view-menu-label">{{ t((item as any).extra.pane.titleKey) }}</span>
            </a>
          </template>
          <template v-else-if="(item as any).extra?.kind === 'action'">
            <a
              v-bind="props.action"
              role="menuitem"
              @click="(item as any).extra.command()"
            >
              <i class="view-menu-check" aria-hidden="true"></i>
              <i :class="(item as any).extra.icon" class="view-menu-pane-icon" aria-hidden="true"></i>
              <span class="view-menu-label">{{ (item as any).extra.label }}</span>
            </a>
          </template>
        </template>
      </Menu>
    </div>

    <!-- User info row -->
    <div
      class="border-t"
      :class="collapsed ? 'p-2' : 'p-4'"
      style="border-color: var(--p-surface-800);"
    >
      <div class="flex items-center" :class="{ 'justify-center': collapsed }">
        <div
          class="h-8 w-8 rounded-full flex items-center justify-center shrink-0"
          style="background-color: var(--p-surface-700);"
          v-tooltip.right="collapsed ? userName : undefined"
        >
          <span class="text-xs font-bold text-white">{{ userInitials }}</span>
        </div>
        <div v-if="!collapsed" class="ml-3 min-w-0">
          <p class="text-sm font-medium text-white line-clamp-1">{{ userName }}</p>
          <Button
            text
            plain
            size="small"
            class="!p-0 !text-xs sign-out"
            :aria-label="t('nav.user.signOutAria')"
            @click="authStore.logout()"
          >
            {{ t('nav.user.signOut') }}
          </Button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.side-nav {
  width: 16rem; /* 256 px expanded */
  transition: width 180ms ease;
}
.side-nav--collapsed {
  width: 3.5rem; /* 56 px collapsed */
}

.collapse-toggle {
  color: #fff !important;
  width: 2rem;
  height: 2rem;
  padding: 0 !important;
}
.collapse-toggle:hover {
  background-color: var(--p-primary-900) !important;
  color: #fff !important;
}

/* PrimeVue text+plain Buttons default to surface-on-light styling. We're on a
 * dark sidebar, so override the colors using --p-surface-* tokens. */
.nav-item {
  /* Idle items use the same bright text as the hover/active states so they
   * stay legible on the dark sidebar; hover is signalled by a dimmed dark-red
   * background instead. */
  color: #fff;
  border-radius: 0.5rem;
  padding: 0.625rem 0.75rem;
  transition: background-color 150ms ease, color 150ms ease;
  /* PrimeVue 4 sets `justify-content: center` on `.p-button` with higher
   * specificity than Tailwind's `.justify-start`, so override here. */
  justify-content: flex-start !important;
  align-items: center;
}
.nav-item:hover {
  background-color: var(--p-primary-900) !important;
  color: #fff !important;
}
.nav-item--active,
.nav-item--active:hover {
  background-color: var(--p-primary-600) !important;
  color: #fff !important;
  box-shadow: 0 6px 16px -8px rgba(220, 38, 38, 0.55);
}

/* Centered icon when collapsed — the label is hidden so the icon needs to
 * sit in the middle of the 56 px wide button. */
.nav-item--collapsed {
  justify-content: center !important;
  padding-left: 0 !important;
  padding-right: 0 !important;
}

/* ── Agents submenu ─────────────────────────────────────────────────────── */
.agents-group {
  margin-bottom: 0.25rem;
}
.agents-chevron {
  font-size: 0.7rem;
  opacity: 0.7;
}
.agents-children {
  margin-top: 0.125rem;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}
/* Nested agent rows: indent so they read as children of the Agents header,
 * with a guide rail down the left. */
.nav-subitem {
  padding-left: 2.25rem;
  margin-left: 0.5rem;
  border-left: 1px solid var(--p-surface-800);
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
}
.nav-subitem.nav-item--active {
  border-left-color: var(--p-primary-600);
}

.view-menu-wrap {
  padding-bottom: 0.5rem;
}

.sign-out {
  color: #fff !important;
}
.sign-out:hover {
  color: #fff !important;
  background-color: var(--p-primary-900) !important;
}
</style>

<style>
/* Unscoped: PrimeVue's Menu is teleported to body, so the .p-menu DOM lives
 * outside this component. The classes below target items rendered through
 * the #item slot for the View menu only — keys on `.view-menu-*`. */
.view-menu-check {
  width: 1rem;
  display: inline-block;
  text-align: center;
  font-size: 0.85rem;
}
.view-menu-pane-icon {
  margin-left: 0.5rem;
  margin-right: 0.5rem;
  font-size: 0.9rem;
}
.view-menu-label {
  flex: 1;
  font-size: 0.85rem;
}
</style>
