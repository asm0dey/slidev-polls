package site.asm0dey.slidev.polls.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class Argon2EncoderSanityTest {

  @Test
  void encodesAndMatchesWithOwaspStrongParams() {
    Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 4, 65536, 3);
    long start = System.nanoTime();
    String hash = encoder.encode("correct-horse");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(hash).startsWith("$argon2id$");
    assertThat(hash).contains("m=65536");
    assertThat(hash).contains("t=3");
    assertThat(hash).contains("p=4");
    assertThat(encoder.matches("correct-horse", hash)).isTrue();
    assertThat(encoder.matches("wrong-horse", hash)).isFalse();
    // Tripwire: blow up if someone bumps params and encode time drifts past 2s on CI.
    // 2000ms accommodates slow shared GitHub runners while still catching real param bloat.
    assertThat(elapsedMs).isLessThan(2_000L);
  }
}
