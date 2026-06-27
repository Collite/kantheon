import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import { fileURLToPath } from "node:url";
import basicSsl from '@vitejs/plugin-basic-ssl';


export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // Single backend origin for the whole FE (Iris Phase 2 Stage 2.2 re-point).
  const bff_target = env.VITE_BFF_BASE_URL || 'http://localhost:7410'
  const server_port = parseInt(env.VITE_SERVER_PORT || '7099', 10)


  const plugins = [
    vue(),
    vueDevTools(),
  ]

  if (env.VITE_HTTPS_ENABLED === 'true') {
    plugins.push(basicSsl())
  }

  return {
    plugins,
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        // Iris Phase 2 Stage 2.2: the generated envelope/v1 + iris/v1 bindings,
        // consumed as TS source from the monorepo (no dist) via a path alias.
        '@kantheon/envelope-ts': fileURLToPath(
          new URL('../../shared/libs/ts/envelope-ts/src/index.ts', import.meta.url),
        ),
      },
    },
    base: '/',
    server: {
      port: server_port,
      proxy: {
        // SSE-tuned proxy for the single BFF backend (Iris Phase 2 Stage 2.2).
        '/bff': {
          target: bff_target,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/bff/, ''),
          configure: (proxy, _options) => {
            proxy.on('error', (err, _req, _res) => {
              console.log('proxy error', err);
            });
            proxy.on('proxyReq', (proxyReq, _req, _res) => {
              proxyReq.setHeader('Connection', 'keep-alive');
            });
            proxy.on('proxyRes', (proxyRes, _req, _res) => {
              proxyRes.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate';
              proxyRes.headers['Connection'] = 'keep-alive';
              proxyRes.headers['Transfer-Encoding'] = 'chunked';
              proxyRes.headers['X-Accel-Buffering'] = 'no';
            });
          }
        }
      }
    }
  }
})
