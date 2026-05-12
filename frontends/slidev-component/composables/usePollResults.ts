import { computed, reactive, readonly, type ComputedRef } from "vue";
import type { SnapshotEvent } from "@slidev-polls/shared";

// Deck-wide reactive cache of the latest SnapshotEvent per poll slug, optionally
// scoped to a specific questionId so multiple PollPanels on the same slug (one
// per question) don't overwrite each other. PollPanel auto-registers via
// setPollResults on every SSE update. Other slides read via usePollResults and
// compose their own aggregates.

const STORAGE_KEY = "slidev-polls:results-cache";

function keyFor(slug: string, questionId?: string): string {
  return questionId ? `${slug}::${questionId}` : slug;
}

function hydrate(): Record<string, SnapshotEvent> {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    const out: Record<string, SnapshotEvent> = {};
    for (const [slug, value] of Object.entries(parsed as Record<string, unknown>)) {
      if (isSnapshot(value)) out[slug] = value;
    }
    return out;
  } catch {
    return {};
  }
}

function isSnapshot(v: unknown): v is SnapshotEvent {
  if (!v || typeof v !== "object") return false;
  const o = v as Record<string, unknown>;
  return (
    typeof o.pollId === "string" &&
    typeof o.slug === "string" &&
    typeof o.emittedAt === "string" &&
    "activeQuestion" in o &&
    Array.isArray(o.tally)
  );
}

const store = reactive<Record<string, SnapshotEvent>>(hydrate());

function persist(): void {
  if (typeof window === "undefined") return;
  try {
    if (Object.keys(store).length === 0) {
      window.localStorage.removeItem(STORAGE_KEY);
    } else {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(store));
    }
  } catch {
    // Quota / privacy mode — store remains in-memory; not fatal.
  }
}

export function setPollResults(slug: string, snapshot: SnapshotEvent, questionId?: string): void {
  store[keyFor(slug, questionId)] = snapshot;
  persist();
}

export function clearPollResults(): void {
  for (const k of Object.keys(store)) delete store[k];
  persist();
}

export function usePollResults(
  slug: string,
  questionId?: string
): ComputedRef<SnapshotEvent | null> {
  const k = keyFor(slug, questionId);
  return computed(() => store[k] ?? null);
}

export function usePollResultsMap(): Readonly<Record<string, SnapshotEvent>> {
  return readonly(store) as Readonly<Record<string, SnapshotEvent>>;
}
