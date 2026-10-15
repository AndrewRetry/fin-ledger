import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end smoke tests against a running stack: `docker compose up --build` (console on :3000), or
 * `npm run dev` with the backend on :8080. Not run in CI; they need the whole stack.
 */
export default defineConfig({
  testDir: 'tests/e2e',
  workers: 1,
  timeout: 60_000,
  reporter: 'list',
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } }],
})
