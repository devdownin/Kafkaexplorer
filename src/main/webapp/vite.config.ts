/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: false,
    // Les tests vivent à côté du code (*.test.tsx / *.test.ts).
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  },
  build: {
    outDir: '../resources/static',
    emptyOutDir: true,
    // monaco reste volumineux (chunk isolé, chargé en parallèle et mis en
    // cache) — on relève le seuil d'alerte pour ne pas polluer le build.
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // Isole les grosses dépendances dans des chunks vendor stables, mis en
        // cache indépendamment du code applicatif et partagés entre les pages
        // (recharts par Metrics + ProcessMining). L'`index` initial reste petit.
        manualChunks(id) {
          if (!id.includes('node_modules')) return;
          if (id.includes('monaco-editor')) return 'monaco';
          if (id.includes('recharts') || id.includes('/d3-') || id.includes('victory-vendor')) return 'charts';
          if (/[\\/]react(-dom|-router|-router-dom)?[\\/]/.test(id)) return 'react-vendor';
        },
      },
    },
  },
})
