// Phase 8 (FE-8.1): vue-i18n + PrimeVue ConfigProvider locale.
//
// We bridge two locale systems:
//   1. vue-i18n — for our own component strings (`t('agent.send')`, etc.).
//   2. PrimeVue's `<ConfigProvider locale>` — for built-in component
//      messages (DataTable empty messages, paginator, calendar weekdays).
//
// The language picker drives both via `setLocale(lang)`.
import { createI18n } from 'vue-i18n'
import en from './en.json'
import cs from './cs.json'

export type Lang = 'en' | 'de' | 'cs' | 'sk' | 'hu'

// Phase 8 ships en + cs catalogues; the picker still offers de/sk/hu
// because the BE prompt yamls cover those languages, but the FE strings
// fall back to English until those locale files are added.
export const SUPPORTED_LOCALES: Lang[] = ['en', 'cs']

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, cs },
})

type LoadedLocale = 'en' | 'cs'

export const setLocale = (lang: Lang | string) => {
  // Catalogue lookup falls back to English for unsupported codes; the
  // global Pinia `prompt language` is still tracked separately so the
  // BE keeps localizing prompts even when we don't have the FE strings
  // for that language.
  const target: LoadedLocale = (SUPPORTED_LOCALES as string[]).includes(lang)
    ? (lang as LoadedLocale)
    : 'en'
  i18n.global.locale.value = target
  // Reflect in <html lang="…"> for screen readers + SEO hygiene.
  if (typeof document !== 'undefined') {
    document.documentElement.setAttribute('lang', target)
  }
}

// PrimeVue locale messages — translated equivalents of the small set of
// strings PrimeVue ships out of the box. Used by `<ConfigProvider
// :locale>` in App.vue. We only need to override the keys the user
// actually sees in v2 (DataTable empty + a few paginator labels);
// PrimeVue accepts a partial dict.
export const primevueLocaleFor = (lang: Lang | string): Record<string, unknown> => {
  if (lang === 'cs') {
    return {
      emptyMessage: 'Žádné záznamy k zobrazení',
      emptyFilterMessage: 'Žádné záznamy neodpovídají filtru',
      apply: 'Použít',
      clear: 'Vymazat',
      noFilter: 'Žádný filtr',
      addRule: 'Přidat pravidlo',
      removeRule: 'Odebrat pravidlo',
      matchAll: 'Splnit vše',
      matchAny: 'Splnit alespoň jedno',
      cancel: 'Zrušit',
    }
  }
  // English defaults are already PrimeVue's built-ins; an empty object
  // means "use defaults".
  return {}
}
