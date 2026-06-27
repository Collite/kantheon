import { createApp } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import { VueQueryPlugin } from '@tanstack/vue-query'
import 'primeicons/primeicons.css'

import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { initAuth } from './services/auth'

// Stage 1.3 shell bootstrap: PrimeVue (Aura) + Pinia + vue-router + TanStack
// Query. Auth is established before mount (Keycloak when enabled, a dev bearer
// locally) so the route guard sees a session.
const pinia = createPinia()
setActivePinia(pinia) // initAuth() reads the session store before mount

const app = createApp(App)
app.use(pinia)
app.use(i18n)
app.use(PrimeVue, { theme: { preset: Aura } })
app.use(ToastService)
app.use(ConfirmationService)
app.use(VueQueryPlugin)

initAuth()
  .catch((e) => console.error('auth init failed', e))
  .finally(() => {
    app.use(router)
    app.mount('#app')
  })
