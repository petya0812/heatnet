import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';
import {fileURLToPath, URL} from 'node:url';

// The interface is built into the jar's static folder: Maven and Docker serve it as they did before.
// `npm run dev` proxies the API to a running service (docker compose, port 8080).
const backend = process.env.HEATNET_API || 'http://localhost:8080';

export default defineConfig({
  base: './',
  plugins: [vue(), tailwindcss()],
  resolve: {alias: {'@': fileURLToPath(new URL('./src', import.meta.url))}},
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {output: {manualChunks: {maplibre: ['maplibre-gl']}}},
  },
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/v3': backend,
      '/swagger-ui': backend,
      '/swagger-ui.html': backend,
      '/webjars': backend,
    },
  },
});
