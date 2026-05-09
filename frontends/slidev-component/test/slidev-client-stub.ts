// Minimal stand-in for @slidev/client used only by vitest. The real module
// imports a virtual #slidev/configs entry point that only resolves inside
// slidev's own vite pipeline; outside slidev (unit tests), nothing emits
// it. The components under test invoke useSlideContext() inside a
// try/catch, so returning a sentinel that throws on lookup is also fine —
// here we return shape-compatible empty values to keep the call cheap.

import { ref } from "vue";

export const configs: Record<string, unknown> = {};

// Match the runtime shape of @slidev/client/env's slideWidth (a Ref<number>);
// 980 is slidev's default canvasWidth so the test sees the same panel sizing
// the deck would.
export const slideWidth = ref(980);

export function useSlideContext(): {
  $slidev: { configs: Record<string, unknown> };
  $frontmatter: Record<string, unknown>;
} {
  return {
    $slidev: { configs },
    $frontmatter: {}
  };
}
