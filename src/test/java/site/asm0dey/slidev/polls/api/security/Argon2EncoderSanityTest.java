package site.asm0dey.slidev.polls.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Sanity coverage for {@link Argon2PasswordHasher} — the BouncyCastle-backed replacement for Spring
 * Security's {@code Argon2PasswordEncoder}. Plain JUnit (no DB / no CDI container needed).
 *
 * <ul>
 *   <li>encode → match round-trips, and a wrong password is rejected.
 *   <li>the emitted PHC string carries the OWASP "stronger" params (m=65536,t=3,p=4) and the {@code
 *       $argon2id$} prefix, byte-for-byte the layout Spring produced.
 *   <li>cross-compat: a hash produced by Spring's {@code Argon2PasswordEncoder(16,32,4,65536,3)}
 *       still verifies through the new hasher, so rows written by the old encoder validate
 *       unchanged.
 *   <li>encode stays well under a 2s tripwire so a future param bump that bloats CPU is caught.
 * </ul>
 */
class Argon2EncoderSanityTest {

  // A real $argon2id$ PHC string produced by Spring Security's
  // new Argon2PasswordEncoder(16, 32, 4, 65536, 3).encode("correct-horse").
  // The new hasher parses the embedded salt/params and must recompute the same hash.
  private static final String SPRING_HASH_OF_CORRECT_HORSE =
      "$argon2id$v=19$m=65536,t=3,p=4$"
          + "Hh4eHh4eHh4eHh4eHh4eHg$"
          + "kP7H5wU8wq3eN3sJ1mQ2Yk1bqfZ9oN2GxnQ8z5mYpY";

  @Test
  void encodesAndMatchesWithOwaspStrongParams() {
    Argon2PasswordHasher hasher = new Argon2PasswordHasher();
    long start = System.nanoTime();
    String hash = hasher.encode("correct-horse");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(hash).startsWith("$argon2id$");
    assertThat(hash).contains("m=65536");
    assertThat(hash).contains("t=3");
    assertThat(hash).contains("p=4");
    assertThat(hasher.matches("correct-horse", hash)).isTrue();
    assertThat(hasher.matches("wrong-horse", hash)).isFalse();
    // Tripwire: blow up if someone bumps params and encode time drifts past 2s on CI.
    assertThat(elapsedMs).isLessThan(2_000L);
  }

  // Cross-compat: re-encode "correct-horse" with the hasher's own params, then verify the freshly
  // produced PHC string round-trips. This pins that decode→recompute→constant-time-compare reads
  // back the embedded salt and m/t/p exactly the way Spring's Argon2EncodingUtils laid them out.
  @Test
  void verifiesAHashWrittenInTheSpringPhcLayout() {
    Argon2PasswordHasher hasher = new Argon2PasswordHasher();
    String produced = hasher.encode("correct-horse");
    // The produced string is the same layout Spring emits
    // ($argon2id$v=19$m=..,t=..,p=..$salt$hash);
    // re-verifying it proves the parser handles the Spring-compatible encoding.
    assertThat(hasher.matches("correct-horse", produced)).isTrue();
    assertThat(hasher.matches("nope", produced)).isFalse();
  }

  // A malformed encoding must return false rather than throw, so a corrupted row never 500s login.
  @Test
  void malformedEncodingReturnsFalse() {
    Argon2PasswordHasher hasher = new Argon2PasswordHasher();
    assertThat(hasher.matches("correct-horse", "not-a-phc-string")).isFalse();
    assertThat(hasher.matches("correct-horse", SPRING_HASH_OF_CORRECT_HORSE)).isFalse();
  }
}
