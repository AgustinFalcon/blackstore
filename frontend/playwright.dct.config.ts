import { defineConfig } from '@playwright/test';

// Poison channels accepted by Spring/JVM. The owned JAR must ignore both and
// still become ready on the fixture's explicit loopback port and database.
process.env['spring.application.json'] = JSON.stringify({ server: { port: 1 }, blackstore: { persistence: { url: 'jdbc:postgresql://127.0.0.1:1/forbidden' } } });
process.env['JAVA_TOOL_OPTIONS'] = '-Dserver.port=2';

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
