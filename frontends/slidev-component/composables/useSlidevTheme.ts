import { onMounted, onUnmounted, ref, type Ref } from "vue";

export type SlidevMode = "light" | "dark";
export type SlidevScrim = "none" | "scrim-dark" | "scrim-light";

export interface SlidevTheme {
  mode: SlidevMode;
  scrim: SlidevScrim;
}

function parseRgb(s: string): [number, number, number] | null {
  const m = s.match(/rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/i);
  if (!m) return null;
  return [Number(m[1]), Number(m[2]), Number(m[3])];
}

function relativeLuminance(rgb: [number, number, number]): number {
  const [r, g, b] = rgb.map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function checkElForImage(el: HTMLElement): boolean {
  const inlineVar = el.style.getPropertyValue("--slidev-bg-image").trim();
  if (inlineVar.length > 0) return true;
  // Check inline backgroundImage before falling back to computed style — Slidev
  // writes the frontmatter `background:` as inline style and jsdom's computed
  // backgroundImage is unreliable for inline-set values.
  const inlineBg = el.style.backgroundImage;
  if (inlineBg && inlineBg !== "none" && /url\(/i.test(inlineBg)) return true;
  const cs = getComputedStyle(el);
  if (cs.getPropertyValue("--slidev-bg-image").trim().length > 0) return true;
  if (cs.backgroundImage && cs.backgroundImage !== "none" && /url\(/i.test(cs.backgroundImage))
    return true;
  if (el.querySelector(":scope > img")) return true;
  return false;
}

// Slidev applies frontmatter `background:` as an inline style on the inner
// .slidev-layout child (a sibling of the slide content), not on .slidev-page.
// Detection must check both — otherwise an image-bg slide registers as plain
// and the panel renders fully opaque against the photo.
function hasImageBackground(slideRoot: HTMLElement): boolean {
  if (checkElForImage(slideRoot)) return true;
  const layout = slideRoot.querySelector<HTMLElement>(":scope > .slidev-layout");
  return layout ? checkElForImage(layout) : false;
}

function effectiveBgRoot(slideRoot: HTMLElement): HTMLElement {
  return slideRoot.querySelector<HTMLElement>(":scope > .slidev-layout") ?? slideRoot;
}

export function resolveSlidevTheme(slideRoot: HTMLElement | null): SlidevTheme {
  if (!slideRoot) return { mode: "light", scrim: "none" };
  const cs = getComputedStyle(effectiveBgRoot(slideRoot));
  const rgb = parseRgb(cs.backgroundColor) ?? [255, 255, 255];
  const lum = relativeLuminance(rgb);
  const mode: SlidevMode = lum > 0.6 ? "light" : "dark";
  const isImg = hasImageBackground(slideRoot);
  let scrim: SlidevScrim = "none";
  if (isImg) scrim = mode === "light" ? "scrim-light" : "scrim-dark";
  return { mode, scrim };
}

export function findSlideRoot(start: HTMLElement | null): HTMLElement | null {
  let n: HTMLElement | null = start;
  while (n) {
    if (n.classList?.contains("slidev-page")) return n;
    n = n.parentElement;
  }
  return null;
}

export function useSlidevTheme(rootEl: Ref<HTMLElement | null>) {
  const theme = ref<SlidevTheme>({ mode: "light", scrim: "none" });
  let observer: MutationObserver | null = null;

  function recompute() {
    const slide = findSlideRoot(rootEl.value);
    theme.value = resolveSlidevTheme(slide);
  }

  onMounted(() => {
    recompute();
    observer = new MutationObserver(recompute);
    if (rootEl.value) {
      const slide = findSlideRoot(rootEl.value);
      if (slide) {
        observer.observe(slide, {
          attributes: true,
          attributeFilter: ["style", "class"]
        });
        // Slidev mutates the inner .slidev-layout's inline `style` when the
        // frontmatter background changes — observe it too so the panel
        // recomputes scrim on per-slide bg swaps.
        const layout = slide.querySelector<HTMLElement>(":scope > .slidev-layout");
        if (layout) {
          observer.observe(layout, {
            attributes: true,
            attributeFilter: ["style", "class"]
          });
        }
      }
    }
  });

  onUnmounted(() => observer?.disconnect());

  return theme;
}
