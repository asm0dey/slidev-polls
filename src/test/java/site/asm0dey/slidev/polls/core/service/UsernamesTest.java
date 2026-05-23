package site.asm0dey.slidev.polls.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UsernamesTest {

  @Test
  void normalize_lowercasesAndTrims() {
    assertThat(Usernames.normalize("  Alice  ")).isEqualTo("alice");
    assertThat(Usernames.normalize("BOB_99")).isEqualTo("bob_99");
  }

  @Test
  void normalize_rejectsTooShort() {
    assertThatThrownBy(() -> Usernames.normalize("ab"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalize_rejectsIllegalChars() {
    assertThatThrownBy(() -> Usernames.normalize("a b!"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalize_rejectsNull() {
    // Pass via a variable so static-analysis does not flag a known-null call;
    // the test intent is to verify that null input is rejected at runtime.
    String nullValue = null;
    assertThatThrownBy(() -> Usernames.normalize(nullValue))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
