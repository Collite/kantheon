import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { SuggestedChip } from '@/types/chips'

export const useChipStore = defineStore('chips', () => {
  const staticChips = ref<SuggestedChip[]>([])
  const dynamicChips = ref<SuggestedChip[]>([])

  const setStaticChips = (chips: SuggestedChip[]) => {
    staticChips.value = chips
  }

  const setDynamicChips = (chips: SuggestedChip[]) => {
    dynamicChips.value = chips
  }

  const clearDynamic = () => {
    dynamicChips.value = []
  }

  return {
    staticChips,
    dynamicChips,
    setStaticChips,
    setDynamicChips,
    clearDynamic,
  }
})
