// Per-slug alreadyVoted cache backed by localStorage. The actual voter identity lives in the
// HttpOnly `sp_voter` cookie, which is server-authoritative per the tasks.md clarification on
// T086/T091: the client never reads or writes the cookie. This cache is a UX convenience —
// remembering "I already voted on /my-talk" across reloads so the PollView shows the confirmed
// state before the /api/polls/by-slug/{slug} response lands.

const STORAGE_PREFIX = "slidev-polls:already-voted:";

/** Safe accessor — degrades quietly when localStorage is unavailable (private mode, quota). */
function storage(): Storage | null {
  try {
    if (typeof window === "undefined" || !window.localStorage) {
      return null;
    }
    return window.localStorage;
  } catch {
    return null;
  }
}

/** Read the cached alreadyVoted flag for the slug; defaults to false when nothing is cached. */
export function hasAlreadyVoted(slug: string): boolean {
  if (!slug) return false;
  const store = storage();
  if (!store) return false;
  try {
    return store.getItem(STORAGE_PREFIX + slug) === "1";
  } catch {
    return false;
  }
}

/** Record that the client observed an accepted vote for the slug. Idempotent. */
export function markAlreadyVoted(slug: string): void {
  if (!slug) return;
  const store = storage();
  if (!store) return;
  try {
    store.setItem(STORAGE_PREFIX + slug, "1");
  } catch {
    // Quota / private mode / disabled storage — the server-authoritative cookie still owns
    // the identity, so losing the cache at worst means a momentary "vote" button re-appears
    // on reload. Principle IV: degrade visibly but do not crash.
  }
}

/** Drop the cached flag — used when the server reports the question has rotated. */
export function clearAlreadyVoted(slug: string): void {
  if (!slug) return;
  const store = storage();
  if (!store) return;
  try {
    store.removeItem(STORAGE_PREFIX + slug);
  } catch {
    // Same degradation stance as markAlreadyVoted.
  }
}
