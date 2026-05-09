import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { nextTick } from "vue";
import { useTheme, __resetThemeForTests } from "./useTheme";

function setMatchMedia(matchesDark: boolean) {
  const listeners: Array<(e: MediaQueryListEvent) => void> = [];
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (q: string) => ({
      matches: q.includes("dark") ? matchesDark : false,
      media: q,
      addEventListener: (_: string, cb: (e: MediaQueryListEvent) => void) =>
        listeners.push(cb),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
      onchange: null,
      __fire: (m: boolean) =>
        listeners.forEach(l =>
          l({ matches: m, media: q } as MediaQueryListEvent)
        )
    })
  });
}

describe("useTheme", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute("data-theme");
    __resetThemeForTests();
  });
  afterEach(() => {
    document.documentElement.removeAttribute("data-theme");
  });

  it("defaults to light when no override and OS prefers light", () => {
    setMatchMedia(false);
    const t = useTheme();
    expect(t.mode.value).toBe("light");
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
  });

  it("defaults to dark when OS prefers dark", () => {
    setMatchMedia(true);
    const t = useTheme();
    expect(t.mode.value).toBe("dark");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("respects explicit localStorage override over OS pref", () => {
    setMatchMedia(true);
    localStorage.setItem("sp-theme", "light");
    const t = useTheme();
    expect(t.mode.value).toBe("light");
  });

  it("toggle flips and persists override", () => {
    setMatchMedia(false);
    const t = useTheme();
    t.toggle();
    expect(t.mode.value).toBe("dark");
    expect(localStorage.getItem("sp-theme")).toBe("dark");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("setMode applies and persists", async () => {
    setMatchMedia(false);
    const t = useTheme();
    t.setMode("dark");
    await nextTick();
    expect(t.mode.value).toBe("dark");
    expect(localStorage.getItem("sp-theme")).toBe("dark");
  });
});
