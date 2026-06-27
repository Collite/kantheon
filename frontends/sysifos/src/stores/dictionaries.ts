import { defineStore } from 'pinia'
import { ref } from 'vue'
import { bff } from '@/api/client'

export interface LabelledEntry {
  code: string
  cs: string
  en: string
}
export interface BrokerEntry {
  brokerId: string
  displayName: string
}

/**
 * BFF dictionaries (contracts §3.7), loaded once and cached in Pinia. Backs the
 * currency/kind dropdowns across the screens. The BFF already caches server-side
 * (TTL 10 min); this avoids re-fetching per screen mount.
 */
export const useDictionariesStore = defineStore('dictionaries', () => {
  const currencies = ref<LabelledEntry[]>([])
  const transactionKinds = ref<LabelledEntry[]>([])
  const assetKinds = ref<LabelledEntry[]>([])
  const brokers = ref<BrokerEntry[]>([])
  const loaded = ref(false)

  async function ensureLoaded() {
    if (loaded.value) return
    const [c, tk, ak, b] = await Promise.all([
      bff<LabelledEntry[]>('/dictionaries/currencies'),
      bff<LabelledEntry[]>('/dictionaries/transaction-kinds'),
      bff<LabelledEntry[]>('/dictionaries/asset-kinds'),
      bff<BrokerEntry[]>('/dictionaries/brokers'),
    ])
    currencies.value = c
    transactionKinds.value = tk
    assetKinds.value = ak
    brokers.value = b
    loaded.value = true
  }

  return { currencies, transactionKinds, assetKinds, brokers, loaded, ensureLoaded }
})
