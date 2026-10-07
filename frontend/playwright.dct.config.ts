import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e/dct',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  outputDir: 'work/dct-browser-results',
  reporter: [['list'], ['json', { outputFile: 'work/dct-browser-results/results.json' }]],
  use: { baseURL: 'http://localhost:4201', browserName: 'chromium', trace: 'off', screenshot: 'off', video: 'off' },
});
