import { globalIgnores } from 'eslint/config'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'
import pluginVitest from '@vitest/eslint-plugin'
import pluginOxlint from 'eslint-plugin-oxlint'
import skipFormatting from 'eslint-config-prettier/flat'

export default defineConfigWithVueTs(
  {
    name: 'app/files-to-lint',
    files: ['**/*.{vue,ts,mts,tsx}'],
  },

  // Generated proto bindings are not human-authored — never lint them.
  globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**', 'src/generated/**']),

  ...pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,

  {
    ...pluginVitest.configs.recommended,
    files: ['src/**/__tests__/*'],
  },

  {
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      // Route views are conventionally single-word (Clients, Import, Audit…).
      'vue/multi-word-component-names': 'off',
    },
  },

  ...pluginOxlint.configs['flat/recommended'],

  skipFormatting,
)
