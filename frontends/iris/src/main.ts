import './assets/main.css'
import 'primeicons/primeicons.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import Tooltip from 'primevue/tooltip'

import App from './App.vue'
import router from './router'
import { initializeTelemetry, type Telemetry } from './telemetry'

import { initKeycloak } from './services/keycloak'
import { config } from "@/config"
import { primevueOptions } from "@/config/primevue"
import { i18n } from "@/i18n"

const telemetryEnabled = config.otel.enabled

const noopTelemetry: Telemetry = {
    logger: {
        emit: () => { },
        emitEvent: () => { },
    } as any,
    traceProvider: {} as any,
    meterProvider: {} as any,
    metrics: {
        httpRequestCounter: { add: () => { } } as any,
        httpRequestDuration: { record: () => { } } as any,
        fuzzyMatchCounter: { add: () => { } } as any,
        fuzzyMatchDuration: { record: () => { } } as any,
    },
}

export const telemetry = telemetryEnabled ? initializeTelemetry('agents-fe') : noopTelemetry

const app = createApp(App)

app.use(createPinia())
app.use(i18n)
app.use(PrimeVue, primevueOptions)
app.use(ToastService)
app.use(ConfirmationService)
app.directive('tooltip', Tooltip)

initKeycloak().then(() => {
    app.use(router)
    app.mount('#app')
}).catch(console.error)
