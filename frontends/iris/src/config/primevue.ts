import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'

import { brand, surface, yellow } from './brand'

/**
 * Aura with the Tatrman palette (`project/common/graphics/tatrman.html`).
 * `--p-primary-*` resolves to Tatrman Yellow, `--p-surface-*` to the
 * Charcoal → Stage Navy ramp; every consumer of those tokens follows.
 *
 * **The load-bearing line is `inverseColor`.** It is the text drawn *on* the primary
 * colour, and Aura's default is white — which on #FFCB2E is about 1.6:1, i.e.
 * invisible. Every filled Button, Tag and highlight in the app would have shipped
 * unreadable. Charcoal on the same yellow is ~5.9:1, and it is what the manual asks
 * for anyway: "yellow = the living parts; charcoal/white = structure".
 *
 * In dark mode the surface is the Stage Navy end of the ramp — the manual's "stage
 * the marionette performs on" — and `highlight` is a translucent yellow rather than
 * a solid fill, so selection reads as a spotlight instead of a yellow slab.
 */
export const AuraTatrman = definePreset(Aura, {
  semantic: {
    primary: yellow,
    colorScheme: {
      light: {
        primary: {
          color: '{primary.500}',
          // Charcoal, not white — see the note above.
          inverseColor: brand.charcoal,
          hoverColor: '{primary.600}',
          activeColor: '{primary.700}',
        },
        highlight: {
          background: '{primary.100}',
          focusBackground: '{primary.200}',
          color: brand.charcoal,
          focusColor: brand.charcoal,
        },
        surface,
      },
      dark: {
        primary: {
          color: '{primary.500}',
          inverseColor: brand.stageNavy,
          hoverColor: '{primary.400}',
          activeColor: '{primary.300}',
        },
        highlight: {
          // Translucent so the spotlight sits on the stage rather than covering it.
          background: 'rgba(255, 203, 46, 0.16)',
          focusBackground: 'rgba(255, 203, 46, 0.24)',
          color: brand.ice,
          focusColor: '#FFFFFF',
        },
        surface,
      },
    },
  },
})

export const themeMode = (import.meta.env.VITE_PRIMEVUE_THEME_MODE ?? 'light') as
  | 'light'
  | 'dark'
  | 'auto'

export const primevueOptions = {
  theme: {
    preset: AuraTatrman,
    options: {
      prefix: 'p',
      darkModeSelector: '.p-dark',
      cssLayer: false,
    },
  },
}
