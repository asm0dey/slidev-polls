// Per-(slug, questionId) alreadyVoted cache backed by localStorage. The actual voter identity
// lives in the HttpOnly `sp_voter` cookie, which is server-authoritative per the tasks.md
// clarification on T086/T091: the client never reads or writes the cookie. This cache is a UX
// convenience — remembering "I already voted on /my-talk's current question" across reloads so
// the PollView shows the confirmed state before the /api/polls/by-slug/{slug} response lands.
//
// Keying on (slug, questionId) — not slug alone — is required since 003: presenters can CLOSE a
// question and re-OPEN any earlier one (or another). A poll-wide flag would falsely report
// "already voted" against a question the voter has not yet answered.

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

function key(slug: string, questionId: string): string {
  return `${STORAGE_PREFIX}${slug}:${questionId}`;
}

/** Read the cached alreadyVoted flag for the (slug, questionId) pair. */
export function hasAlreadyVoted(slug: string, questionId: string): boolean {
  if (!slug || !questionId) return false;
  const store = storage();
  if (!store) return false;
  try {
    return store.getItem(key(slug, questionId)) === "1";
  } catch {
    return false;
  }
}

/** Record that the client observed an accepted vote for the (slug, questionId). Idempotent. */
export function markAlreadyVoted(slug: string, questionId: string): void {
  if (!slug || !questionId) return;
  const store = storage();
  if (!store) return;
  try {
    store.setItem(key(slug, questionId), "1");
  } catch {
    // Quota / private mode / disabled storage — the server-authoritative cookie still owns
    // the identity, so losing the cache at worst means a momentary "vote" button re-appears
    // on reload. Principle IV: degrade visibly but do not crash.
  }
}

/**
 * Drop the cached flag for one specific (slug, questionId), or — when questionId is omitted —
 * every cached question for the slug. Used when the server reports the active question has
 * rotated or when a previously-CLOSED question is re-OPENED on the same slug.
 */
export function clearAlreadyVoted(slug: string, questionId?: string): void {
  if (!slug) return;
  const store = storage();
  if (!store) return;
  try {
    if (questionId) {
      store.removeItem(key(slug, questionId));
      return;
    }
    const prefix = `${STORAGE_PREFIX}${slug}:`;
    const drop: string[] = [];
    for (let i = 0; i < store.length; i++) {
      const k = store.key(i);
      if (k && k.startsWith(prefix)) drop.push(k);
    }
    drop.forEach((k) => store.removeItem(k));
  } catch {
    // Same degradation stance as markAlreadyVoted.
  }
}
