import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { clearAlreadyVoted, hasAlreadyVoted, markAlreadyVoted } from "./voterFlag";

// Coverage for the alreadyVoted localStorage cache. The fake storage used by jsdom picks up
// any direct window.localStorage calls; tests wipe the keyspace before each case so ordering
// does not leak state.

const Q1 = "11111111-1111-1111-1111-111111111111";
const Q2 = "22222222-2222-2222-2222-222222222222";

function wipeStorage() {
  for (let i = window.localStorage.length - 1; i >= 0; i--) {
    const key = window.localStorage.key(i);
    if (key) window.localStorage.removeItem(key);
  }
}

describe("voterFlag", () => {
  beforeEach(wipeStorage);

  afterEach(wipeStorage);

  it("returns false for an unknown (slug, questionId) pair", () => {
    expect(hasAlreadyVoted("my-talk", Q1)).toBe(false);
  });

  it("remembers an accepted vote for one (slug, questionId)", () => {
    markAlreadyVoted("my-talk", Q1);
    expect(hasAlreadyVoted("my-talk", Q1)).toBe(true);
  });

  it("scopes cache by questionId — voting on Q1 does not flag Q2 as voted", () => {
    markAlreadyVoted("my-talk", Q1);
    expect(hasAlreadyVoted("my-talk", Q1)).toBe(true);
    expect(hasAlreadyVoted("my-talk", Q2)).toBe(false);
  });

  it("scopes cache by slug too", () => {
    markAlreadyVoted("talk-a", Q1);
    expect(hasAlreadyVoted("talk-a", Q1)).toBe(true);
    expect(hasAlreadyVoted("talk-b", Q1)).toBe(false);
  });

  it("is idempotent — repeat mark keeps the flag true", () => {
    markAlreadyVoted("my-talk", Q1);
    markAlreadyVoted("my-talk", Q1);
    expect(hasAlreadyVoted("my-talk", Q1)).toBe(true);
  });

  it("clearAlreadyVoted with questionId removes only that pair", () => {
    markAlreadyVoted("my-talk", Q1);
    markAlreadyVoted("my-talk", Q2);
    clearAlreadyVoted("my-talk", Q1);
    expect(hasAlreadyVoted("my-talk", Q1)).toBe(false);
    expect(hasAlreadyVoted("my-talk", Q2)).toBe(true);
  });

  it("clearAlreadyVoted without questionId wipes every cached question for the slug", () => {
    markAlreadyVoted("my-talk", Q1);
    markAlreadyVoted("my-talk", Q2);
    markAlreadyVoted("other-talk", Q1);
    clearAlreadyVoted("my-talk");
    expect(hasAlreadyVoted("my-talk", Q1)).toBe(false);
    expect(hasAlreadyVoted("my-talk", Q2)).toBe(false);
    // Different slug stays untouched.
    expect(hasAlreadyVoted("other-talk", Q1)).toBe(true);
  });

  it("guards against empty / falsy slug or questionId", () => {
    markAlreadyVoted("", Q1);
    expect(hasAlreadyVoted("", Q1)).toBe(false);
    markAlreadyVoted("my-talk", "");
    expect(hasAlreadyVoted("my-talk", "")).toBe(false);
    // Nothing written — neighbouring cache entries stay untouched.
    markAlreadyVoted("other", Q1);
    expect(hasAlreadyVoted("other", Q1)).toBe(true);
  });
});
