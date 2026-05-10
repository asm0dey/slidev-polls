import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, devices } from "@playwright/test";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Shared Playwright config for the voter SPA and the Slidev addon e2e
// smokes (tests are added in T075 and T106). Each SPA keeps its specs
// in its own `e2e/` folder; a project per package keeps failures
// attributable.
//
// slidev-chromium depends on voter-chromium: the cross-origin spec signs
// into the deck auth UI and the backend's /api/deck/auth/login mints a
// token for the presenter's most-recently-created poll. If the full-flow
// voter spec were still creating polls concurrently, the token could be
// scoped to the wrong poll. The project dependency ensures all voter specs
// (and their poll-creation side-effects) complete before any slidev spec
// fires its deck sign-in.
//
// Backend provisioning is handled by the Taskfile (`task test:e2e`) — it
// brings up `compose.dev.yml` if :8080 is idle and tears it down after
// both projects finish.
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
      use: { ...devices["Desktop Chrome"] },
      dependencies: ["voter-chromium"]
    }
  ]
});
