import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { i18n } from './i18n'
import App from './App.vue'
import './assets/main.css'

import { initKeycloak } from './services/keycloak'
import { initializeTelemetry, type Telemetry } from './telemetry'

const telemetryEnabled = import.meta.env.VITE_LANDING_OTEL_ENABLED !== 'false'

const noopTelemetry: Telemetry = {
    logger: {
        emit: () => { },
        emitEvent: () => { },
    } as any,
    traceProvider: {} as any,
    meterProvider: {} as any,
}

export const telemetry = telemetryEnabled ? initializeTelemetry('landing') : noopTelemetry

const app = createApp(App)
app.use(createPinia())

app.directive('click-outside', {
  mounted(el, binding) {
    el._clickOutside = (event: MouseEvent) => {
      if (!(el === event.target || el.contains(event.target as Node))) {
        binding.value(event)
      }
    }
    document.addEventListener('click', el._clickOutside)
  },
  unmounted(el) {
    document.removeEventListener('click', el._clickOutside)
  },
})

app.use(i18n)

initKeycloak().then((authenticated) => {
  if (authenticated) {
    app.mount('#app')
  } else {
    console.warn("User is not authenticated, awaiting Keycloak redirect...")
  }
}).catch(console.error)
