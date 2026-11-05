import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e/odr',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:4217', browserName: 'chromium', trace: 'off', screenshot: 'off', video: 'off' },
  webServer: {
    command: 'node node_modules/@angular/cli/bin/ng.js serve --host 127.0.0.1 --port 4217',
    url: 'http://127.0.0.1:4217',
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
