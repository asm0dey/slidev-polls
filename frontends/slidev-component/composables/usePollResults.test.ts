import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { SnapshotEvent } from "@slidev-polls/shared";

const STORAGE_KEY = "slidev-polls:results-cache";

function makeSnapshot(slug: string, count = 1): SnapshotEvent {
  return {
    pollId: `poll-${slug}`,
    slug,
    emittedAt: "2026-05-12T10:00:00Z",
    activeQuestion: {
      id: `q-${slug}`,
      prompt: `Prompt for ${slug}`,
      ordinal: 1,
      options: [
        { id: "a", label: "A", position: 0 },
        { id: "b", label: "B", position: 1 }
      ]
    },
    tally: [
      { optionId: "a", count },
      { optionId: "b", count: count + 1 }
    ]
  };
}

async function loadFresh() {
  // Re-import so module-singleton state and hydration logic re-run after we
  // tweak localStorage. Vitest caches ESM by default; resetModules clears it.
  const { vi } = await import("vitest");
  vi.resetModules();
  return await import("./usePollResults");
}

describe("usePollResults", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    window.localStorage.clear();
  });

  it("hydrates the store from localStorage on first import", async () => {
    const cached = { quiz: makeSnapshot("quiz", 5) };
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(cached));

    const mod = await loadFresh();
    const result = mod.usePollResults("quiz");

    expect(result.value).not.toBeNull();
    expect(result.value?.tally[0].count).toBe(5);
  });

  it("ignores malformed localStorage payloads", async () => {
    window.localStorage.setItem(STORAGE_KEY, "{not json");

    const mod = await loadFresh();
    expect(mod.usePollResultsMap()).toEqual({});
  });

  it("persists each set call to localStorage", async () => {
    const mod = await loadFresh();
    mod.setPollResults("quiz", makeSnapshot("quiz", 2));

    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw!) as Record<string, SnapshotEvent>;
    expect(parsed.quiz.tally[0].count).toBe(2);
  });

  it("notifies reactive consumers when a slug is updated", async () => {
    const mod = await loadFresh();
    const { computed, watchSyncEffect } = await import("vue");

    const result = mod.usePollResults("quiz");
    const seen: (number | undefined)[] = [];
    const stop = watchSyncEffect(() => {
      seen.push(computed(() => result.value?.tally[0].count).value);
    });

    mod.setPollResults("quiz", makeSnapshot("quiz", 1));
    mod.setPollResults("quiz", makeSnapshot("quiz", 7));
    stop();

    expect(seen).toEqual([undefined, 1, 7]);
  });

  it("clearPollResults empties the store and the cache", async () => {
    const mod = await loadFresh();
    mod.setPollResults("quiz", makeSnapshot("quiz"));
    mod.clearPollResults();

    expect(mod.usePollResultsMap()).toEqual({});
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("keeps separate entries under distinct author-chosen keys", async () => {
    const mod = await loadFresh();
    mod.setPollResults("q1", makeSnapshot("poll", 3));
    mod.setPollResults("q2", makeSnapshot("poll", 9));

    expect(mod.usePollResults("q1").value?.tally[0].count).toBe(3);
    expect(mod.usePollResults("q2").value?.tally[0].count).toBe(9);
    expect(mod.usePollResults("poll").value).toBeNull();
  });
});
