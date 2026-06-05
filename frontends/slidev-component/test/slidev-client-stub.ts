// Minimal stand-in for @slidev/client used only by vitest. The real module
// imports a virtual #slidev/configs entry point that only resolves inside
// slidev's own vite pipeline; outside slidev (unit tests), nothing emits
// it. The components under test invoke useSlideContext() inside a
// try/catch, so returning a sentinel that throws on lookup is also fine —
// here we return shape-compatible empty values to keep the call cheap.

import { ref } from "vue";

export const configs: Record<string, unknown> = {};

// Per-slide frontmatter the stubbed useSlideContext() reports. Tests flip this
// (e.g. __setFrontmatter({ pollServer: "..." })) to exercise the frontmatter
// branch of useVoterUrl. Defaults to empty so existing tests are unaffected.
let frontmatter: Record<string, unknown> = {};
export function __setFrontmatter(fm: Record<string, unknown>): void {
  frontmatter = fm;
}

// Match the runtime shape of @slidev/client/env's slideWidth (a Ref<number>);
// 980 is slidev's default canvasWidth so the test sees the same panel sizing
// the deck would.
export const slideWidth = ref(980);

// Render context the stubbed useSlideContext() reports. Tests flip this to
// 'previewNext' / 'overview' to exercise PollPanel's render-context gate
// (the real @slidev/client exposes $renderContext as a Ref). Defaults to
// 'slide' so existing tests see the normal main-view activate behaviour.
export type StubRenderContext = "slide" | "presenter" | "previewNext" | "overview";
let renderContext: StubRenderContext = "slide";
export function __setRenderContext(ctx: StubRenderContext): void {
  renderContext = ctx;
}

export function useSlideContext(): {
  $slidev: { configs: Record<string, unknown> };
  $frontmatter: Record<string, unknown>;
  $renderContext: { value: StubRenderContext };
} {
  return {
    $slidev: { configs },
    $frontmatter: frontmatter,
    $renderContext: ref(renderContext)
  };
}
