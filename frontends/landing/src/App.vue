<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LanguagePicker from '@/components/LanguagePicker.vue'
import { config } from '@/config'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const authStore = useAuthStore()

const userName = computed(() => {
  if (!authStore.user) return 'Loading...'
  return authStore.user.name || authStore.user.preferred_username || 'User'
})

const userInitials = computed(() => {
  if (!authStore.user) return '?'
  const name = authStore.user.name || authStore.user.preferred_username || 'U'
  return name.substring(0, 2).toUpperCase()
})


const leftLinks = [
  { nameKey: 'links.agent', url: config.links.agent || '#' },
  { nameKey: 'links.services', url: config.links.services || '#' },
  { nameKey: 'links.devPortal', url: config.links.devPortal || '#' },
  { nameKey: 'links.grafana', url: config.links.grafana || '#' },
]

const rightLinks = [
  { nameKey: 'links.argocd', url: config.links.argocd || '#' },
  { nameKey: 'links.traefik', url: config.links.traefik || '#' },
  { nameKey: 'links.keycloak', url: config.links.keycloak || '#' },
]

type ServiceStatus = 'healthy' | 'degraded' | 'down' | 'unknown'

interface Service {
  id: string
  name: string
  url?: string
  // Key into the health-check service's technology map (matches a `technologies.*`
  // entry in the health service config). Falls back to `id` when omitted.
  tech?: string
  status?: ServiceStatus
}

interface Category {
  id: string
  name: string
  services: Service[]
}

interface HealthTechnology {
  name: string
  status: string
}

const categories = ref<Category[]>([])

// Single source of truth: the in-cluster health-check service reaches every backend
// (HTTP, TCP, Prometheus) — the browser can't, so per-service direct fetches used to
// mark internal services (Postgres, Grafana, Loki, …) as down even when healthy.
onMounted(async () => {
  let cats: Category[] = []
  try {
    const response = await fetch('/services.json', { cache: 'no-cache' })
    if (response.ok) {
      cats = (await response.json()).categories || []
    }
  } catch (error) {
    console.error('Failed to load services configuration:', error)
  }

  // Fetch the aggregate health snapshot and index it by technology name.
  const statusByTech = new Map<string, ServiceStatus>()
  try {
    const base = config.healthUrl.replace(/\/$/, '')
    const res = await fetch(`${base}/health/all/detailed`, { method: 'GET', cache: 'no-cache' })
    if (res.ok || res.status === 503) {
      // /health/all returns 503 when *any* technology is unhealthy, but the body
      // still carries the full per-technology breakdown we need.
      const data = await res.json()
      for (const tech of (data.technologies || []) as HealthTechnology[]) {
        statusByTech.set(tech.name, tech.status === 'healthy' ? 'healthy' : 'down')
      }
    }
  } catch (error) {
    console.error('Failed to load health snapshot:', error)
  }

  cats.forEach(category => {
    category.services.forEach(service => {
      const key = service.tech || service.id
      service.status = statusByTech.get(key) ?? 'unknown'
    })
  })
  categories.value = cats
})
</script>

<template>
  <div class="min-h-screen bg-gray-50 flex flex-col">
    <!-- Header -->
    <header class="bg-white shadow-sm border-b border-gray-200">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
        <div class="flex items-center justify-between">
          <div class="flex items-center space-x-3">
            <!-- Logo -->
            <img src="@/assets/logo.jpg" alt="Logo" class="h-10 w-auto rounded-lg object-contain" />
            <h1 class="text-xl font-semibold text-gray-900">{{ t('header.title') }}</h1>
          </div>

          <div class="flex items-center space-x-6">
            <LanguagePicker />

            <!-- User Profile -->
            <div class="flex items-center pl-6 border-l border-gray-200">
              <div class="h-8 w-8 rounded-full bg-gray-200 flex items-center justify-center mr-3">
                <span class="text-xs font-bold text-gray-600">{{ userInitials }}</span>
              </div>
              <div class="flex flex-col">
                <span class="text-sm font-medium text-gray-900 leading-none">{{ userName }}</span>
                <a @click="authStore.logout()" class="text-xs text-indigo-600 hover:text-indigo-800 cursor-pointer mt-1 font-medium transition-colors">Sign Out</a>
              </div>
            </div>
          </div>
        </div>
      </div>
    </header>

    <!-- Main Content -->
    <main class="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6">
      <!-- Top Half: Platform Health -->
      <section class="mb-6">
        <div class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
          <div class="px-4 py-3 border-b border-gray-200 bg-gray-50">
            <h2 class="text-sm font-medium text-gray-700">{{ t('dashboard.title') || 'Platform Health' }}</h2>
          </div>
          <div class="p-6">
            <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
              <div v-for="category in categories" :key="category.id" class="bg-white rounded-lg shadow-sm border border-gray-200">
                <div class="px-3 py-2 border-b border-gray-200 bg-gray-50">
                  <h3 class="text-xs font-semibold text-gray-700 uppercase tracking-wider">{{ category.name }}</h3>
                </div>
                <div class="p-3">
                  <!-- Summary -->
                  <div class="grid grid-cols-3 gap-2 text-center mb-4">
                    <div class="bg-gray-50 p-1.5 rounded border border-gray-100">
                      <div class="text-xl font-bold text-green-600">{{ category.services.filter(s => s.status === 'healthy').length }}</div>
                      <div class="text-[10px] text-gray-500 uppercase tracking-wider">Healthy</div>
                    </div>
                    <div class="bg-gray-50 p-1.5 rounded border border-gray-100">
                      <div class="text-xl font-bold text-orange-500">{{ category.services.filter(s => s.status === 'degraded').length }}</div>
                      <div class="text-[10px] text-gray-500 uppercase tracking-wider">Degraded</div>
                    </div>
                    <div class="bg-gray-50 p-1.5 rounded border border-gray-100">
                      <div class="text-xl font-bold text-red-600">{{ category.services.filter(s => s.status === 'down').length }}</div>
                      <div class="text-[10px] text-gray-500 uppercase tracking-wider">Down</div>
                    </div>
                  </div>
                  <!-- Services -->
                  <div class="grid grid-cols-2 gap-2">
                    <div v-for="service in category.services" :key="service.id" class="flex items-center justify-between p-1.5 border border-gray-200 rounded">
                      <span class="text-xs font-medium text-gray-700 truncate mr-2" :title="service.name">{{ service.name }}</span>
                      <div
                        class="w-2.5 h-2.5 rounded-full flex-shrink-0"
                        :class="{
                          'bg-green-500': service.status === 'healthy',
                          'bg-orange-500': service.status === 'degraded',
                          'bg-red-500': service.status === 'down',
                          'bg-gray-300': service.status === 'unknown' || !service.status
                        }"
                      ></div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- Bottom Half: Service Links -->
      <section>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
          <!-- Left Column -->
          <div class="bg-white rounded-lg shadow-sm border border-gray-200">
            <div class="px-4 py-3 border-b border-gray-200 bg-gray-50">
              <h2 class="text-sm font-medium text-gray-700">{{ t('links.applications') }}</h2>
            </div>
            <ul class="divide-y divide-gray-200">
              <li v-for="link in leftLinks" :key="link.nameKey">
                <a
                  :href="link.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="block px-4 py-3 hover:bg-gray-50 transition-colors duration-150"
                >
                  <span class="text-sm font-medium text-gray-900">{{ t(link.nameKey) }}</span>
                </a>
              </li>
            </ul>
          </div>

          <!-- Right Column -->
          <div class="bg-white rounded-lg shadow-sm border border-gray-200">
            <div class="px-4 py-3 border-b border-gray-200 bg-gray-50">
              <h2 class="text-sm font-medium text-gray-700">{{ t('links.infrastructure') }}</h2>
            </div>
            <ul class="divide-y divide-gray-200">
              <li v-for="link in rightLinks" :key="link.nameKey">
                <a
                  :href="link.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="block px-4 py-3 hover:bg-gray-50 transition-colors duration-150"
                >
                  <span class="text-sm font-medium text-gray-900">{{ t(link.nameKey) }}</span>
                </a>
              </li>
            </ul>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>
