package site.asm0dey.slidev.polls.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Vote(
    UUID id,
    UUID pollId,
    UUID questionId,
    List<UUID> optionIds,
    String voterToken,
    Instant createdAt) {

  public Vote {
    optionIds = optionIds == null ? List.of() : List.copyOf(optionIds);
  }
}
