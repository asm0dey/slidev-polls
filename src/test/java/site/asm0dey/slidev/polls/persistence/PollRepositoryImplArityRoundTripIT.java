package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Verifies that per-question arity ({@code min_selections}, {@code max_selections}) round-trips
 * through {@link PollRepositoryImpl}'s insert path and multiset hydration. Exercised end-to-end via
 * the application's {@link PollService} so the column defaults / NOT NULL constraints from Flyway
 * are exercised.
 *
 * <p>Ported to {@code @QuarkusTest}: the prior {@code @SpringBootTest} + Testcontainers boot is
 * replaced by Dev Services Postgres and CDI injection of the production beans; the presenter admin
 * row is seeded via {@link AdminUserTestFixtures} against the injected {@code @Default} {@link
 * DSLContext}.
 */
@QuarkusTest
class PollRepositoryImplArityRoundTripIT {

  @Inject PollService pollService;
  @Inject PollRepository pollRepository;
  @Inject DSLContext dsl;
  @Inject Argon2PasswordHasher encoder;

  @BeforeEach
  void seedPresenter() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "presenter", "correct-horse");
  }

  @Test
  void mixedArityQuestionsSurviveRoundTrip() {
    pollService.create(
        "presenter",
        new CreatePollCommand(
            "demo",
            "arity-demo",
            List.of(
                new CreatePollCommand.QuestionDraft(
                    "pick one",
                    1,
                    1,
                    List.of(
                        new CreatePollCommand.OptionDraft("a"),
                        new CreatePollCommand.OptionDraft("b"))),
                new CreatePollCommand.QuestionDraft(
                    "pick any",
                    0,
                    3,
                    List.of(
                        new CreatePollCommand.OptionDraft("x"),
                        new CreatePollCommand.OptionDraft("y"),
                        new CreatePollCommand.OptionDraft("z")))),
            List.of()));

    var poll = pollRepository.findBySlug("arity-demo").orElseThrow();

    assertThat(poll.questions().get(0).minSelections()).isEqualTo(1);
    assertThat(poll.questions().get(0).maxSelections()).isEqualTo(1);
    assertThat(poll.questions().get(1).minSelections()).isZero();
    assertThat(poll.questions().get(1).maxSelections()).isEqualTo(3);
  }
}
