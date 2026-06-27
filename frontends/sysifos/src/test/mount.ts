import { mount, type ComponentMountingOptions } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import { VueQueryPlugin } from '@tanstack/vue-query'
import type { Component } from 'vue'
import { i18n } from '@/i18n'

/** Mount a component with the app's plugin stack (Pinia, i18n, PrimeVue, Query). */
export function mountC<C extends Component>(component: C, options: ComponentMountingOptions<C> = {}) {
  return mount(component, {
    ...options,
    global: {
      plugins: [createPinia(), i18n, [PrimeVue, {}], ToastService, ConfirmationService, VueQueryPlugin],
      ...options.global,
    },
  })
}
