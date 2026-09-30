import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: './',
  // JCEF embeds app.js under a nonce CSP and cannot fetch separate Mermaid chunks.
  build: { assetsInlineLimit: 0, rollupOptions: { output: { inlineDynamicImports: true, entryFileNames: 'app.js', chunkFileNames: '[name].js', assetFileNames: '[name][extname]' } } },
});
