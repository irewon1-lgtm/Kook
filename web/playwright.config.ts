import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';

export default defineConfig({
  testDir: './e2e', timeout: 120_000, expect: { timeout: 20_000 },
  fullyParallel: false, workers: 1,
  outputDir: process.env.KR4_E2E_OUTPUT_DIR || 'test-results/runner',
  reporter: [['list'], ['html', { open: 'never' }], ['json', { outputFile: 'test-results/results.json' }]],
  use: {
    baseURL: process.env.KR4_PUBLIC_URL || 'http://127.0.0.1:4173/Kook/',
    viewport: { width: 1365, height: 853 }, locale: 'ko-KR', timezoneId: 'Asia/Seoul',
    screenshot: 'only-on-failure', trace: 'retain-on-failure',
    channel: process.env.KR4_BROWSER_CHANNEL as 'chrome' | 'msedge' | undefined,
    launchOptions: existsSync('/usr/bin/chromium') ? { executablePath: '/usr/bin/chromium' } : {},
  },
  webServer: process.env.KR4_PUBLIC_URL ? undefined : {
    command: 'npm run dev -- --port 4173 --strictPort', url: 'http://127.0.0.1:4173/Kook/',
    reuseExistingServer: !process.env.CI, timeout: 120_000,
  },
});
