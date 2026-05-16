package site.asm0dey.slidev.polls.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuestionArityTest {

  @Test
  void defaultArityIsOneToOne() {
    Question q = new Question(UUID.randomUUID(), UUID.randomUUID(), "p", 0,
        QuestionStatus.DRAFT, 1, 1, List.of(), null, null);
    assertThat(q.minSelections()).isEqualTo(1);
    assertThat(q.maxSelections()).isEqualTo(1);
  }

  @Test
  void minMustNotExceedMax() {
    assertThatThrownBy(
            () -> new Question(UUID.randomUUID(), UUID.randomUUID(), "p", 0,
                QuestionStatus.DRAFT, 3, 2, List.of(), null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void minMayBeZero() {
    Question q = new Question(UUID.randomUUID(), UUID.randomUUID(), "p", 0,
        QuestionStatus.DRAFT, 0, 3, List.of(), null, null);
    assertThat(q.minSelections()).isZero();
  }

  @Test
  void maxMustBeAtLeastOne() {
    assertThatThrownBy(
            () -> new Question(UUID.randomUUID(), UUID.randomUUID(), "p", 0,
                QuestionStatus.DRAFT, 0, 0, List.of(), null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
