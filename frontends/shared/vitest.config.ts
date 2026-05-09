import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: "jsdom",
    globals: false,
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    // sse-client.test.ts uses `bun:test`; it is run by `bun test`, not vitest.
    exclude: ["src/sse-client.test.ts"]
  }
});
