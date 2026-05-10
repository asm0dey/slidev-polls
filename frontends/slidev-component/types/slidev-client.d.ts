// Stub for `@slidev/client`. The real package ships untranspiled .ts source
// that depends on Slidev's build-time global defines and virtual modules,
// which only resolve inside Slidev's own Vite pipeline. tsconfig paths
// redirects the import here so vue-tsc doesn't follow into the real source.
import type { Ref } from "vue";

export const slideWidth: Ref<number>;

export function useSlideContext(): {
  $slidev: { configs: Record<string, unknown> };
  $frontmatter: Record<string, unknown>;
};
