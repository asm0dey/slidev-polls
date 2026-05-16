package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import site.asm0dey.slidev.polls.api.PollApiApplication;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;
import site.asm0dey.slidev.polls.core.service.CreatePollCommand;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Verifies that per-question arity ({@code min_selections}, {@code max_selections}) round-trips
 * through {@link PollRepositoryImpl}'s insert path and multiset hydration. Boots the real
 * application against Testcontainers Postgres so the column defaults / NOT NULL constraints from
 * Flyway V11 are exercised end-to-end.
 */
@SpringBootTest(
    classes = PollApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TestcontainersConfiguration.class)
class PollRepositoryImplArityRoundTripIT {

  @Autowired PollService pollService;
  @Autowired PollRepository pollRepository;
  @Autowired DSLContext dsl;
  @Autowired PasswordEncoder encoder;

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
