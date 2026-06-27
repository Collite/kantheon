import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// Minimal wiki browse FE over the kallimachos-mcp `library.*` surface (P4 S4.2).
// `/library` proxies to the MCP wrapper in dev; the bearer is the user's OBO token.
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      "/library": { target: process.env.KALLIMACHOS_MCP_URL ?? "http://localhost:7262", changeOrigin: true },
    },
  },
});
