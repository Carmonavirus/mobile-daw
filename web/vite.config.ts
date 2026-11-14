import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

// base './' so the build works from any folder, e.g. https://<user>.github.io/mobile-daw/
export default defineConfig({
  base: './',
  plugins: [preact()],
  define: {
    __BUILD__: JSON.stringify((process.env.GITHUB_SHA ?? 'dev').slice(0, 7)),
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
  },
  server: { fs: { allow: ['..'] } },
  build: { target: 'es2020', outDir: 'dist' },
});
