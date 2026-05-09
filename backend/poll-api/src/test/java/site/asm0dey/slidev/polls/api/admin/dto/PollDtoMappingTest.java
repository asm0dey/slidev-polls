package site.asm0dey.slidev.polls.api.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;

/**
 * Unit coverage for {@link PollDto}/{@link PollDetailDto} mapping. Having these assertions sit in
 * plain unit tests (rather than only exercising them through an integration test) keeps the mapping
 * cheap to evolve — every controller change otherwise needs a Testcontainers boot to validate the
 * URL shape.
 */
class PollDtoMappingTest {

  // @TS-002 — the join link shape must match /[a-z0-9-]{3,40} suffixed to the public base URL.
  @Test
  void poll_dto_composes_public_url_from_base_and_slug() {
    Poll poll = fixturePoll("my-talk");

    PollDto dto = PollDto.from(poll, "http://example.test");

    assertThat(dto.publicUrl()).isEqualTo("http://example.test/my-talk");
    assertThat(dto.slug()).isEqualTo("my-talk");
    assertThat(dto.status()).isEqualTo(PollStatus.DRAFT);
  }

  // Guard against a trailing-slash base producing `//slug`; presenters paste whatever their
  // browser serves up, so the mapping must tolerate both forms.
  @Test
  void poll_dto_strips_trailing_slash_from_base() {
    Poll poll = fixturePoll("my-talk");

    PollDto dto = PollDto.from(poll, "http://example.test/");

    assertThat(dto.publicUrl()).isEqualTo("http://example.test/my-talk");
  }

  // @TS-002 — PollDetail exposes questions + options + style alongside the summary fields; the
  // style map round-trips through PollStyleDto without dropping keys the OpenAPI schema names.
  @Test
  void poll_detail_dto_carries_questions_options_and_style() {
    Poll poll = fixturePoll("my-talk", Map.of("primaryColor", "#ff0000", "layout", "BARS"));

    PollDetailDto detail = PollDetailDto.from(poll, "http://example.test");

    assertThat(detail.questions()).hasSize(1);
    assertThat(detail.questions().get(0).options()).hasSize(2);
    assertThat(detail.style().primaryColor()).isEqualTo("#ff0000");
    assertThat(detail.style().layout()).isEqualTo("BARS");
  }

  private static Poll fixturePoll(String slug) {
    return fixturePoll(slug, Map.of());
  }

  private static Poll fixturePoll(String slug, Map<String, Object> style) {
    UUID pollId = UUID.randomUUID();
    UUID qid = UUID.randomUUID();
    List<Option> opts =
        List.of(
            new Option(UUID.randomUUID(), qid, "A", 0), new Option(UUID.randomUUID(), qid, "B", 1));
    Question q = new Question(qid, pollId, "prompt?", 0, QuestionStatus.DRAFT, opts, null, null);
    return new Poll(
        pollId,
        "alice",
        "Title",
        slug,
        PollStatus.DRAFT,
        style,
        null,
        List.of(q),
        List.of(), // allowedOrigins
        Instant.now(),
        Instant.now());
  }
}
