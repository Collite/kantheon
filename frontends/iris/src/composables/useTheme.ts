import { onMounted } from 'vue'
import { themeMode } from '@/config/primevue'

const DARK_CLASS = 'p-dark'

const applyDarkMode = (enabled: boolean) => {
  const html = document.documentElement
  if (enabled) html.classList.add(DARK_CLASS)
  else html.classList.remove(DARK_CLASS)
}

const resolveDark = (): boolean => {
  if (themeMode === 'dark') return true
  if (themeMode === 'light') return false
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

export function useTheme() {
  onMounted(() => {
    applyDarkMode(resolveDark())
    if (themeMode === 'auto' && window.matchMedia) {
      const mq = window.matchMedia('(prefers-color-scheme: dark)')
      mq.addEventListener('change', (e) => applyDarkMode(e.matches))
    }
  })
}
