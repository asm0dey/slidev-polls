package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.core.domain.Option;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.domain.PollStatus;
import site.asm0dey.slidev.polls.core.domain.Question;
import site.asm0dey.slidev.polls.core.domain.QuestionStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests for {@link PollRepositoryImpl} against a real Postgres instance
 * (Testcontainers). Covers round-trip persistence of poll fields beyond what the
 * concurrency-focused ITs cover.
 */
class PollRepositoryImplTest extends AbstractPostgresTest {

  private PollRepositoryImpl repo;

  @BeforeEach
  void setUp() {
    DSLContext dsl = dsl();
    repo = new PollRepositoryImpl(dsl, JsonMapper.builder().build());
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, "repo-test-owner")
        .set(ADMIN_USER.PASSWORD_HASH, "n/a")
        .set(ADMIN_USER.CREATED_AT, OffsetDateTime.now())
        .onConflictDoNothing()
        .execute();
  }

  // @TS-A3-001 — allowed_origins written on insert are returned unchanged on findById.
  @Test
  void persistsAllowedOriginsRoundTrip() {
    Poll poll =
        buildPoll(
            "round-trip-origins", List.of("http://localhost:3030", "https://demo.example.com"));
    Poll inserted = repo.insert(poll);
    Poll loaded = repo.findById(inserted.id()).orElseThrow();
    assertThat(loaded.allowedOrigins())
        .containsExactly("http://localhost:3030", "https://demo.example.com");
  }

  // @TS-A3-002 — updateAllowedOrigins replaces the origins list and returns the updated poll.
  @Test
  void updateAllowedOriginsReplacesOrigins() {
    Poll poll = buildPoll("update-origins", List.of("http://old.example.com"));
    Poll inserted = repo.insert(poll);

    Poll updated =
        repo.updateAllowedOrigins(
            inserted.id(), List.of("http://new1.example.com", "http://new2.example.com"));
    assertThat(updated.allowedOrigins())
        .containsExactly("http://new1.example.com", "http://new2.example.com");

    // Confirm persistence via a fresh load.
    Poll reloaded = repo.findById(inserted.id()).orElseThrow();
    assertThat(reloaded.allowedOrigins())
        .containsExactly("http://new1.example.com", "http://new2.example.com");
  }

  // @TS-A3-003 — updateAllowedOrigins with an empty list clears all origins.
  @Test
  void updateAllowedOriginsClearsWhenEmpty() {
    Poll poll = buildPoll("clear-origins", List.of("http://localhost:3030"));
    Poll inserted = repo.insert(poll);

    Poll updated = repo.updateAllowedOrigins(inserted.id(), List.of());
    assertThat(updated.allowedOrigins()).isEmpty();
  }

  private static Poll buildPoll(String slugSuffix, List<String> allowedOrigins) {
    UUID pollId = UUID.randomUUID();
    UUID q1 = UUID.randomUUID();
    List<Option> options =
        new ArrayList<>(
            List.of(
                new Option(UUID.randomUUID(), q1, "A", 0),
                new Option(UUID.randomUUID(), q1, "B", 1)));
    return new Poll(
        pollId,
        "repo-test-owner",
        "Test poll",
        slugSuffix + "-" + pollId.toString().substring(0, 8),
        PollStatus.DRAFT,
        Map.of(),
        null,
        List.of(new Question(q1, pollId, "Q?", 0, QuestionStatus.DRAFT, options, null, null)),
        allowedOrigins,
        null,
        null);
  }
}
