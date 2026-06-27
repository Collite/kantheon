import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { config } from '@/config'

/** Id of the first configured agent — the default landing agent. */
const firstAgentId = () => config.golemAgents[0]?.id ?? 'golem'

const getDynamicBase = () => {
  if (window.location.pathname.startsWith('/agents/fe')) {
    return '/agents/fe'
  }
  return '/'
}

const router = createRouter({
  history: createWebHistory(getDynamicBase()),
  routes: [
    {
      path: '/',
      redirect: () => `/agents/${firstAgentId()}`
    },
    {
      // Legacy single-agent path — keep redirecting existing bookmarks.
      path: '/agent',
      redirect: () => `/agents/${firstAgentId()}`
    },
    {
      path: '/agents/:agentId',
      name: 'agent',
      component: () => import('../views/AgentView.vue'),
      // `props: true` forwards the `:agentId` route param as the `agentId` prop.
      props: true,
    },
  ],
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()

  if (!authStore.isAuthenticated) {
    authStore.login()
    return next(false)
  }

  next()
})

export default router