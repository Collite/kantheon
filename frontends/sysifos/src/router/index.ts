import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useSessionStore } from '@/stores/session'

// One route per §8 screen; all behind the auth guard except /login.
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/clients' },
  { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { public: true } },
  { path: '/clients', name: 'clients', component: () => import('@/views/Clients.vue') },
  { path: '/portfolios', name: 'portfolios', component: () => import('@/views/Portfolios.vue') },
  { path: '/assets', name: 'assets', component: () => import('@/views/Assets.vue') },
  { path: '/transactions', name: 'transactions', component: () => import('@/views/Transactions.vue') },
  { path: '/balance-entry', name: 'balance-entry', component: () => import('@/views/BalanceEntry.vue') },
  { path: '/import', name: 'import', component: () => import('@/views/Import.vue') },
  {
    path: '/import/:loaderRunId',
    name: 'import-preview',
    component: () => import('@/views/ImportPreview.vue'),
  },
  { path: '/reconcile', name: 'reconcile', component: () => import('@/views/Reconcile.vue') },
  { path: '/loaders', name: 'loaders', component: () => import('@/views/Loaders.vue') },
  { path: '/audit', name: 'audit', component: () => import('@/views/Audit.vue'), meta: { admin: true } },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const session = useSessionStore()
  if (!to.meta.public && !session.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // Admin-only routes (Audit) — non-admins are bounced to the default screen.
  if (to.meta.admin && !session.hasRole('midas:admin')) {
    return { name: 'clients' }
  }
  return true
})

export default router
