import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, devices } from "@playwright/test";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Shared Playwright config for the voter SPA and the Slidev addon e2e
// smokes (tests are added in T075 and T106). Each SPA keeps its specs
// in its own `e2e/` folder; a project per package keeps failures
// attributable and lets CI run them in parallel.
//
// Backend provisioning is handled by the Taskfile (`task test:e2e:voter`,
// `task test:e2e:slidev`) — it brings up `compose.dev.yml` if :8080 is idle
// and tears it down after the spec exits.
//
// The Slidev dev server for cross-origin e2e is managed here via webServer so
// Playwright waits for :3030 to be ready before running the slidev-chromium
// project specs.
export default defineConfig({
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? "github" : "list",
  fullyParallel: true,
  use: {
    baseURL: process.env.PW_BASE_URL ?? "http://localhost:8080",
    trace: "on-first-retry"
  },
  webServer: {
    command: "bun run dev:e2e",
    cwd: path.resolve(__dirname, "slidev-demo"),
    url: "http://localhost:3030",
    reuseExistingServer: !process.env.CI,
    timeout: 60_000
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
