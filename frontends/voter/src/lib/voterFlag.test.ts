import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { clearAlreadyVoted, hasAlreadyVoted, markAlreadyVoted } from "./voterFlag";

// Coverage for the alreadyVoted localStorage cache. The fake storage used by jsdom picks up
// any direct window.localStorage calls; tests wipe the keyspace before each case so ordering
// does not leak state.

function wipeStorage() {
  // jsdom's localStorage is Storage-shaped but its `clear()` binding is finicky across
  // versions; iterate keys by index and remove individually so the cleanup is robust no
  // matter which jsdom ships.
  for (let i = window.localStorage.length - 1; i >= 0; i--) {
    const key = window.localStorage.key(i);
    if (key) window.localStorage.removeItem(key);
  }
}

describe("voterFlag", () => {
  beforeEach(wipeStorage);

  afterEach(wipeStorage);

  it("returns false for an unknown slug", () => {
    expect(hasAlreadyVoted("my-talk")).toBe(false);
  });

  it("remembers an accepted vote for the slug", () => {
    markAlreadyVoted("my-talk");
    expect(hasAlreadyVoted("my-talk")).toBe(true);
  });

  it("scopes cache by slug", () => {
    markAlreadyVoted("talk-a");
    expect(hasAlreadyVoted("talk-a")).toBe(true);
    expect(hasAlreadyVoted("talk-b")).toBe(false);
  });

  it("is idempotent — repeat mark keeps the flag true", () => {
    markAlreadyVoted("my-talk");
    markAlreadyVoted("my-talk");
    expect(hasAlreadyVoted("my-talk")).toBe(true);
  });

  it("clearAlreadyVoted removes the cached flag", () => {
    markAlreadyVoted("my-talk");
    clearAlreadyVoted("my-talk");
    expect(hasAlreadyVoted("my-talk")).toBe(false);
  });

  it("guards against empty / falsy slugs", () => {
    markAlreadyVoted("");
    expect(hasAlreadyVoted("")).toBe(false);
    // Nothing written — neighbouring cache entries stay untouched.
    markAlreadyVoted("other");
    expect(hasAlreadyVoted("other")).toBe(true);
  });
});
