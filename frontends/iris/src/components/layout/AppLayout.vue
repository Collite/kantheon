<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import SideNavigation from './SideNavigation.vue'
import { useAuthStore } from '@/stores/auth'
import { useLayoutStore } from '@/stores/layoutStore'
import { installLayoutPersistence } from '@/stores/layoutPersistence'

// v2.1 layout persistence boots once with the app shell. AppLayout only
// renders after the auth gate (App.vue) has resolved the user, so
// `authStore.userId` is the real per-user namespace by the time we read it.
//
// Install runs during *setup*, not `onMounted` — Vue's lifecycle calls
// children's setup after the parent's, so doing this here guarantees that
// `layoutStore` already reflects the persisted state by the time
// DockviewWorkspace.onReady fires (which reads splitter sizes & active pane
// from the store).
const authStore = useAuthStore()
const layoutStore = useLayoutStore()

const stopPersistence = installLayoutPersistence(layoutStore, () => authStore.userId)

onBeforeUnmount(() => {
  stopPersistence()
})
</script>

<template>
  <div class="flex h-screen overflow-hidden"
       style="background-color: var(--p-surface-50);">
    <SideNavigation />

    <div class="flex flex-col flex-1 min-w-0 overflow-hidden">
      <main class="flex-1 overflow-y-auto focus:outline-none scroll-smooth">
        <div class="py-6">
          <div class="mx-auto w-full px-4 sm:px-6 md:px-8">
            <slot />
          </div>
        </div>
      </main>
    </div>
  </div>
</template>
