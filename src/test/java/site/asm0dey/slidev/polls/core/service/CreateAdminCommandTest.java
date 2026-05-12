package site.asm0dey.slidev.polls.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CreateAdminCommandTest {

  // The auto-generated record toString() leaks every component verbatim. The override must mask
  // the password so any log line that captured the command (e.g. a Spring debug trace of an
  // exception built via concatenation) does not surface the plaintext.
  @Test
  void toStringMasksPassword() {
    var cmd = new CreateAdminCommand("alice", "correct-horse-battery");

    assertThat(cmd.toString())
        .contains("password=***")
        .doesNotContain("correct-horse-battery")
        .contains("alice");
  }
}
