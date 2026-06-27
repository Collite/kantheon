import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'

// Aura preset with Red as the semantic primary palette.
// `--p-primary-*` CSS variables resolve to the red shades below; PrimeVue
// components and any consumer of those tokens follow the Aura Red brand.
export const AuraRed = definePreset(Aura, {
  semantic: {
    primary: {
      50:  '{red.50}',
      100: '{red.100}',
      200: '{red.200}',
      300: '{red.300}',
      400: '{red.400}',
      500: '{red.500}',
      600: '{red.600}',
      700: '{red.700}',
      800: '{red.800}',
      900: '{red.900}',
      950: '{red.950}',
    },
  },
})

export const themeMode = (import.meta.env.VITE_PRIMEVUE_THEME_MODE ?? 'light') as
  | 'light'
  | 'dark'
  | 'auto'

export const primevueOptions = {
  theme: {
    preset: AuraRed,
    options: {
      prefix: 'p',
      darkModeSelector: '.p-dark',
      cssLayer: false,
    },
  },
}
