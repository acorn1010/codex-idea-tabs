import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: './',
  build: { assetsInlineLimit: 0, rollupOptions: { output: { entryFileNames: 'app.js', chunkFileNames: '[name].js', assetFileNames: '[name][extname]' } } },
});
