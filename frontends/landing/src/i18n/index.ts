import { createI18n } from 'vue-i18n'
import Cookies from 'js-cookie'
import en from './locales/en.json'
import de from './locales/de.json'
import cs from './locales/cs.json'
import sk from './locales/sk.json'
import hu from './locales/hu.json'

export type SupportedLocale = 'en' | 'de' | 'cs' | 'sk' | 'hu'

export const supportedLocales: { code: SupportedLocale; name: string; flag: string }[] = [
  { code: 'en', name: 'English', flag: '🇬🇧' },
  { code: 'de', name: 'Deutsch', flag: '🇩🇪' },
  { code: 'cs', name: 'Čeština', flag: '🇨🇿' },
  { code: 'sk', name: 'Slovenčina', flag: '🇸🇰' },
  { code: 'hu', name: 'Magyar', flag: '🇭🇺' },
]

const savedLocale = Cookies.get('locale') as SupportedLocale | undefined
const browserLocale = navigator.language.split('-')[0] as SupportedLocale
// Default is saved, or 'cs' if not found
const defaultLocale: SupportedLocale = savedLocale || browserLocale || 'cs'

export const i18n = createI18n({
  legacy: false,
  locale: defaultLocale,
  fallbackLocale: 'en',
  messages: {
    en,
    de,
    cs,
    sk,
    hu,
  },
})

export function setLocale(locale: SupportedLocale) {
  i18n.global.locale.value = locale
  Cookies.set('locale', locale, { expires: 365, path: '/' })
}
