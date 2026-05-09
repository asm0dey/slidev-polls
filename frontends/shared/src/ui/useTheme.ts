import { ref, watch, type Ref } from "vue";

export type ThemeMode = "light" | "dark";

const STORAGE_KEY = "sp-theme";

let modeRef: Ref<ThemeMode> | null = null;
let mediaQuery: MediaQueryList | null = null;
let mediaListener: ((e: MediaQueryListEvent) => void) | null = null;

function readOverride(): ThemeMode | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === "light" || v === "dark" ? v : null;
  } catch {
    return null;
  }
}

function osPrefersDark(): boolean {
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
}

function apply(mode: ThemeMode) {
  document.documentElement.setAttribute("data-theme", mode);
}

export function useTheme() {
  if (modeRef) {
    return {
      mode: modeRef,
      setMode,
      toggle
    };
  }

  const initial: ThemeMode = readOverride() ?? (osPrefersDark() ? "dark" : "light");
  modeRef = ref<ThemeMode>(initial);
  apply(initial);

  watch(modeRef, (m) => apply(m));

  mediaQuery = window.matchMedia?.("(prefers-color-scheme: dark)") ?? null;
  if (mediaQuery) {
    mediaListener = (e) => {
      if (readOverride() !== null) return;
      modeRef!.value = e.matches ? "dark" : "light";
    };
    mediaQuery.addEventListener("change", mediaListener);
  }

  return { mode: modeRef, setMode, toggle };
}

function setMode(m: ThemeMode) {
  if (!modeRef) return;
  modeRef.value = m;
  apply(m);
  try {
    localStorage.setItem(STORAGE_KEY, m);
  } catch {
    /* noop */
  }
}

function toggle() {
  if (!modeRef) return;
  setMode(modeRef.value === "light" ? "dark" : "light");
}

export function __resetThemeForTests() {
  if (mediaQuery && mediaListener) {
    mediaQuery.removeEventListener("change", mediaListener);
  }
  modeRef = null;
  mediaQuery = null;
  mediaListener = null;
}
