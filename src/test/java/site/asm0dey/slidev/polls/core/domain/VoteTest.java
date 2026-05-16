package site.asm0dey.slidev.polls.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoteTest {

  @Test
  void carriesArrayOfOptionIds() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    Vote v =
        new Vote(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(a, b),
            "voter-1",
            Instant.now());
    assertThat(v.optionIds()).containsExactly(a, b);
  }

  @Test
  void emptyOptionIdsAllowed() {
    Vote v =
        new Vote(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(),
            "voter-abstain",
            Instant.now());
    assertThat(v.optionIds()).isEmpty();
  }

  @Test
  void optionIdsDefensivelyCopied() {
    java.util.ArrayList<UUID> mutable = new java.util.ArrayList<>(List.of(UUID.randomUUID()));
    Vote v =
        new Vote(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            mutable,
            "voter",
            Instant.now());
    mutable.add(UUID.randomUUID());
    assertThat(v.optionIds()).hasSize(1);
  }
}
