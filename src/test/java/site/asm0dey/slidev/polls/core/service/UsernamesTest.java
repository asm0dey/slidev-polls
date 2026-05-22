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
    assertThatThrownBy(() -> Usernames.normalize(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
