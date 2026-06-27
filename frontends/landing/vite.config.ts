import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from "node:url";
import basicSsl from '@vitejs/plugin-basic-ssl';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  const plugins = [vue()]
  if (env.VITE_HTTPS_ENABLED === 'true') {
    plugins.push(basicSsl())
  }

  return {
    plugins,
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      },
    },
    base: '',
    server: {
      port: env.VITE_LANDING_SERVER_PORT ? parseInt(env.VITE_LANDING_SERVER_PORT) : 7011,
    }
  }
})
