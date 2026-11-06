import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const version = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version;
let revision = 'unknown';
try { revision = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(); } catch { /* An exported archive may have no Git metadata. */ }

export default defineConfig({
  base: process.env.KR4_BASE_PATH || '/Kook/',
  plugins: [react()],
  define: {
    'import.meta.env.VITE_KR4_UI_VERSION': JSON.stringify(version),
    'import.meta.env.VITE_KR4_UI_COMMIT': JSON.stringify(revision),
  },
  test: { include: ['tests/**/*.test.{ts,tsx}'], environment: 'node' },
  build: { sourcemap: true },
});
