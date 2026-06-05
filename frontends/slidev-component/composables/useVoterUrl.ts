import { computed, unref, type ComputedRef } from "vue";
import { useSlideContext } from "@slidev/client";

// Resolves the public voter URL for a poll slug, encoded into a QR code by
// PollQr. Host comes purely from the deck — per-slide frontmatter `pollServer`,
// then deck headmatter (`$slidev.configs.pollServer`), then the browser origin.
// No network and no runtime backend config: a QR is just a printable URL.
export function useVoterUrl(slug: string | (() => string)): ComputedRef<string> {
  const ctx = (() => {
    try {
      return useSlideContext();
    } catch {
      return null;
    }
  })();

  const host = (() => {
    const fm = (ctx?.$frontmatter ?? {}) as Record<string, unknown>;
    if (typeof fm.pollServer === "string" && fm.pollServer.length > 0) {
      return fm.pollServer;
    }
    const cfgs = (ctx?.$slidev?.configs ?? {}) as Record<string, unknown>;
    if (typeof cfgs.pollServer === "string" && cfgs.pollServer.length > 0) {
      return cfgs.pollServer;
    }
    return typeof window !== "undefined" ? window.location.origin : "";
  })();

  return computed(() => {
    const s = typeof slug === "function" ? slug() : unref(slug);
    return `${host.replace(/\/$/, "")}/${s}`;
  });
}
