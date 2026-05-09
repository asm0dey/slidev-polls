import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath } from "node:url";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: [
      // @slidev/client/env transitively imports the virtual #slidev/configs
      // module that only slidev's own vite plugin emits at runtime. Vitest
      // can't resolve it, so we alias every @slidev/client subpath to a
      // thin stub that returns empty configs. PollPanel calls
      // useSlideContext() inside a try/catch and tolerates an empty result.
      {
        find: /^@slidev\/client(\/.*)?$/,
        replacement: fileURLToPath(new URL("./test/slidev-client-stub.ts", import.meta.url))
      }
    ]
  },
  test: {
    environment: "jsdom",
    globals: false,
    include: [
      "components/**/*.{test,spec}.{ts,tsx}",
      "composables/**/*.{test,spec}.{ts,tsx}"
    ]
  }
});
