import { describe, it, expect } from "vitest";
import { resolveSlidevTheme } from "./useSlidevTheme";

function makeSlide(opts: {
  bg?: string;
  bgImage?: string;
  hasImgChild?: boolean;
}): HTMLElement {
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
});
