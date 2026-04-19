// Mirrors backend/poll-core/src/main/java/.../slug/SlugValidator.java and
// ReservedSlugs.java. Keep this file in lock-step with those constants so
// the presenter sees the same rejection client-side that the backend would
// issue per @TS-011 / @TS-012, without a network round-trip.

const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
export const SLUG_MIN_LENGTH = 3;
export const SLUG_MAX_LENGTH = 40;

export const RESERVED_SLUGS: ReadonlySet<string> = new Set([
  "admin",
  "api",
  "assets",
  "static",
  "j",
  "login",
  "logout"
]);

export type SlugRejectionReason = "EMPTY" | "LENGTH" | "FORMAT" | "RESERVED";

export interface SlugCheckResult {
  valid: boolean;
  reason?: SlugRejectionReason;
  message?: string;
}

export function checkSlug(raw: string): SlugCheckResult {
  if (raw.length === 0) {
    return { valid: false, reason: "EMPTY", message: "Enter a slug." };
  }
  // Reserved check runs before length/format so single-character reserved
  // slugs like "j" surface as RESERVED — matching PollService.resolveSlug,
  // which the @TS-012 acceptance row pins (see tasks.md T044 reconcile note).
  if (RESERVED_SLUGS.has(raw)) {
    return {
      valid: false,
      reason: "RESERVED",
      message: `"${raw}" is a reserved slug. Pick a different name.`
    };
  }
  if (raw.length < SLUG_MIN_LENGTH || raw.length > SLUG_MAX_LENGTH) {
    return {
      valid: false,
      reason: "LENGTH",
      message: `Slug length must be ${SLUG_MIN_LENGTH}–${SLUG_MAX_LENGTH} characters.`
    };
  }
  if (!SLUG_PATTERN.test(raw)) {
    return {
      valid: false,
      reason: "FORMAT",
      message: "Use lowercase letters, digits, and single hyphens (no leading or trailing dash)."
    };
  }
  return { valid: true };
}
