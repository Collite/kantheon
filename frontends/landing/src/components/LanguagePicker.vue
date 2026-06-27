<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { supportedLocales, setLocale, type SupportedLocale } from '@/i18n'

const { locale } = useI18n()
const isOpen = ref(false)

function selectLocale(code: SupportedLocale) {
  setLocale(code)
  isOpen.value = false
}

function toggleDropdown() {
  isOpen.value = !isOpen.value
}

function closeDropdown() {
  isOpen.value = false
}
</script>

<template>
  <div class="relative" v-click-outside="closeDropdown">
    <button
      @click="toggleDropdown"
      class="flex items-center space-x-2 px-3 py-2 rounded-lg hover:bg-gray-100 transition-colors"
    >
      <span class="text-lg">{{ supportedLocales.find(l => l.code === locale)?.flag }}</span>
      <svg class="w-4 h-4 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
      </svg>
    </button>
    
    <Transition
      enter-active-class="transition ease-out duration-100"
      enter-from-class="transform opacity-0 scale-95"
      enter-to-class="transform opacity-100 scale-100"
      leave-active-class="transition ease-in duration-75"
      leave-from-class="transform opacity-100 scale-100"
      leave-to-class="transform opacity-0 scale-95"
    >
      <div
        v-if="isOpen"
        class="absolute right-0 mt-2 w-40 bg-white rounded-lg shadow-lg border border-gray-200 py-1 z-50"
      >
        <button
          v-for="lang in supportedLocales"
          :key="lang.code"
          @click="selectLocale(lang.code)"
          class="w-full flex items-center space-x-3 px-4 py-2 hover:bg-gray-50 transition-colors"
          :class="{ 'bg-gray-50': locale === lang.code }"
        >
          <span class="text-lg">{{ lang.flag }}</span>
          <span class="text-sm text-gray-700">{{ lang.name }}</span>
        </button>
      </div>
    </Transition>
  </div>
</template>
