// End-to-end tests drive the real app: Spring Boot serving the web client, with demo data seeded.
// Locally they reuse a server already running on :8080 (mvn spring-boot:run); in CI they start the packaged jar.
const { defineConfig, devices } = require('@playwright/test');

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

module.exports = defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 7_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: BASE_URL,
    locale: 'en-GB',
    timezoneId: 'Europe/Istanbul',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'] }, testMatch: /layout\.spec\.js/ },
  ],
  webServer: {
    // The suite drives a whole service from one address, faster than any till would; lift the per-client budget.
    command: 'java -jar ../target/restaurant-order-processing.jar --restaurant.security.rate-limit.api-requests-per-minute=100000',
    url: `${BASE_URL}/actuator/health/readiness`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
