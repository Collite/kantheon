import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

// Sysifos FE (Stage 1.1 skeleton). The full app — router, Pinia session store,
// PrimeVue screens — lands in Stage 1.3. Single BFF origin proxied under /bff.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const bff_target = env.VITE_BFF_BASE_URL || 'http://localhost:7601'
  const server_port = parseInt(env.VITE_SERVER_PORT || '7100', 10)

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    base: '/',
    server: {
      port: server_port,
      proxy: {
        // SSE-tuned proxy for the single BFF backend (drafts/stream land 1.2–1.3).
        '/bff': {
          target: bff_target,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/bff/, ''),
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyReq) => {
              proxyReq.setHeader('Connection', 'keep-alive')
            })
            proxy.on('proxyRes', (proxyRes) => {
              proxyRes.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate'
              proxyRes.headers['X-Accel-Buffering'] = 'no'
            })
          },
        },
      },
    },
  }
})
