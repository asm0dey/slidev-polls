import { defineConfig, devices } from "@playwright/test";

// Shared Playwright config for the voter SPA and the Slidev addon e2e
// smokes (tests are added in T075 and T106). Each SPA keeps its specs
// in its own `e2e/` folder; a project per package keeps failures
// attributable and lets CI run them in parallel.
export default defineConfig({
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? "github" : "list",
  fullyParallel: true,
  use: {
    baseURL: process.env.PW_BASE_URL ?? "http://localhost:8080",
    trace: "on-first-retry"
  },
  projects: [
    {
      name: "voter-chromium",
      testDir: "./voter/e2e",
      use: { ...devices["Desktop Chrome"] }
    },
    {
      name: "slidev-chromium",
      testDir: "./slidev-component/e2e",
      use: { ...devices["Desktop Chrome"] }
    }
  ]
});
