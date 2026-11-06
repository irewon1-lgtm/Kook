import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: process.env.KR4_BASE_PATH || '/Kook/',
  plugins: [react()],
  test: { include: ['tests/**/*.test.ts'], environment: 'node' },
  build: { sourcemap: true },
});
