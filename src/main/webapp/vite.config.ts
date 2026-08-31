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
        // Dans le conteneur de dev, `localhost` est le conteneur frontend lui-même :
        // le proxy pointait donc dans le vide et aucun appel API n'aboutissait.
        // VITE_PROXY_TARGET vise le service backend de compose/dev.yml ; le
        // défaut reste inchangé pour un `npm run dev` lancé sur la machine.
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080',
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
        // Règles ancrées sur le chemin du package (node_modules/<pkg>/) : un motif
        // trop large (ex. `includes('monaco-editor')` qui attrapait aussi
        // @monaco-editor/loader) fait colocaliser des petits helpers partagés dans
        // le gros chunk, et l'entrée se met alors à l'importer statiquement —
        // Monaco se retrouvait préchargé sur toutes les pages.
        manualChunks(id) {
          // Le helper de préchargement virtuel de Vite est importé par l'entrée ET
          // par chaque page lazy : sans assignation explicite, Rollup le colocalise
          // dans un gros chunk (monaco) que l'entrée se met alors à précharger.
          if (id.includes('vite/preload-helper')) return 'preload';
          if (!id.includes('node_modules')) return;
          // Même problème pour clsx/tailwind-merge, partagés entre cn() (entrée)
          // et recharts (chunk charts).
          if (/[\\/]node_modules[\\/](clsx|tailwind-merge)[\\/]/.test(id)) return 'ui-utils';
          if (/[\\/]node_modules[\\/]monaco-editor[\\/]/.test(id)) return 'monaco';
          if (/[\\/]node_modules[\\/](recharts|d3-[^\\/]+|victory-vendor)[\\/]/.test(id)) return 'charts';
          if (/[\\/]node_modules[\\/]react(-dom|-router|-router-dom)?[\\/]/.test(id)) return 'react-vendor';
        },
      },
    },
  },
})
