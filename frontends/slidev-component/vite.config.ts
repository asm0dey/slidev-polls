import { defineConfig } from "vite";

// qr-code-styling@1.9.x ships UMD-only. Consumer Slidev decks don't see
// the import via Vite's dep scan (transitive through this addon), so it
// isn't pre-bundled and the browser tries to load raw UMD as ESM, failing
// with: doesn't provide an export named: 'default'. Force pre-bundle.
export default defineConfig({
  optimizeDeps: {
    include: ["qr-code-styling"]
  }
});
