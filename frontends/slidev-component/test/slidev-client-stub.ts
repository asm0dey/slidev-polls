// Minimal stand-in for @slidev/client used only by vitest. The real module
// imports a virtual #slidev/configs entry point that only resolves inside
// slidev's own vite pipeline; outside slidev (unit tests), nothing emits
// it. The components under test invoke useSlideContext() inside a
// try/catch, so returning a sentinel that throws on lookup is also fine —
// here we return shape-compatible empty values to keep the call cheap.

export const configs: Record<string, unknown> = {};

export function useSlideContext(): {
  $slidev: { configs: Record<string, unknown> };
  $frontmatter: Record<string, unknown>;
} {
  return {
    $slidev: { configs },
    $frontmatter: {}
  };
}
