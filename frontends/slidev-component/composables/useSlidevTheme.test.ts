import { describe, it, expect } from "vitest";
import { resolveSlidevTheme } from "./useSlidevTheme";

function makeSlide(opts: { bg?: string; bgImage?: string; hasImgChild?: boolean }): HTMLElement {
  const el = document.createElement("div");
  el.className = "slidev-page";
  if (opts.bg) el.style.background = opts.bg;
  if (opts.bgImage) el.style.setProperty("--slidev-bg-image", opts.bgImage);
  if (opts.hasImgChild) {
    const img = document.createElement("img");
    el.appendChild(img);
  }
  document.body.appendChild(el);
  return el;
}

// Slidev applies frontmatter `background:` as inline style on the inner
// .slidev-layout child of .slidev-page, not on the page itself. Mirror that
// real-world layout so the detection logic is exercised the way it ships.
function makeSlideWithLayout(opts: {
  pageBg?: string;
  layoutBg?: string;
  layoutBgImageUrl?: string;
  layoutBgImageVar?: string;
}): HTMLElement {
  const page = document.createElement("div");
  page.className = "slidev-page";
  if (opts.pageBg) page.style.background = opts.pageBg;
  const layout = document.createElement("div");
  layout.className = "slidev-layout";
  if (opts.layoutBg) layout.style.background = opts.layoutBg;
  if (opts.layoutBgImageUrl) layout.style.backgroundImage = opts.layoutBgImageUrl;
  if (opts.layoutBgImageVar) layout.style.setProperty("--slidev-bg-image", opts.layoutBgImageVar);
  page.appendChild(layout);
  document.body.appendChild(page);
  return page;
}

describe("resolveSlidevTheme", () => {
  it("light mode + no scrim for plain white background", () => {
    const el = makeSlide({ bg: "rgb(255,255,255)" });
    expect(resolveSlidevTheme(el)).toEqual({ mode: "light", scrim: "none" });
  });

  it("dark mode + no scrim for plain dark background", () => {
    const el = makeSlide({ bg: "rgb(10,10,10)" });
    expect(resolveSlidevTheme(el)).toEqual({ mode: "dark", scrim: "none" });
  });

  it("scrim-dark when image background is detected and bg luminance is low", () => {
    const el = makeSlide({ bg: "rgb(20,20,20)", bgImage: "url(p.jpg)" });
    expect(resolveSlidevTheme(el)).toEqual({ mode: "dark", scrim: "scrim-dark" });
  });

  it("scrim-light when image background is detected and bg luminance is high", () => {
    const el = makeSlide({ bg: "rgb(240,240,240)", bgImage: "url(p.jpg)" });
    expect(resolveSlidevTheme(el)).toEqual({ mode: "light", scrim: "scrim-light" });
  });

  it("triggers scrim when slide has direct img child", () => {
    const el = makeSlide({ bg: "rgb(20,20,20)", hasImgChild: true });
    expect(resolveSlidevTheme(el).scrim).toBe("scrim-dark");
  });

  it("detects an image background applied to the inner .slidev-layout", () => {
    // Slidev's real-world layout: frontmatter `background:` lands as inline
    // style on .slidev-layout (child of .slidev-page). Earlier the detector
    // only looked at the page and missed this, leaving the panel opaque.
    const page = makeSlideWithLayout({
      layoutBg: "rgb(20,20,20)",
      layoutBgImageUrl: 'url("/boxers.png")'
    });
    const theme = resolveSlidevTheme(page);
    expect(theme.scrim).toBe("scrim-dark");
    expect(theme.mode).toBe("dark");
  });

  it("detects --slidev-bg-image on the inner .slidev-layout", () => {
    const page = makeSlideWithLayout({
      layoutBg: "rgb(245,245,245)",
      layoutBgImageVar: "url(p.jpg)"
    });
    const theme = resolveSlidevTheme(page);
    expect(theme.scrim).toBe("scrim-light");
    expect(theme.mode).toBe("light");
  });

  it("uses inner .slidev-layout background colour for mode when page has none", () => {
    const page = makeSlideWithLayout({ layoutBg: "rgb(10,10,10)" });
    expect(resolveSlidevTheme(page)).toEqual({ mode: "dark", scrim: "none" });
  });
});
