package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.DECK_TOKENS;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import site.asm0dey.slidev.polls.api.PollApiApplication;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.core.service.DeckTokenRepository;

@SpringBootTest(classes = PollApiApplication.class)
@Import(TestcontainersConfiguration.class)
class DeckTokenMintedByIT {

  @Autowired DSLContext dsl;
  @Autowired DeckTokenRepository repository;

  UUID pollId;

  @BeforeEach
  void seed() {
    dsl.deleteFrom(DECK_TOKENS).execute();
    dsl.deleteFrom(POLLS).execute();
    dsl.deleteFrom(ADMIN_USER).execute();
    insertAdmin("owner");
    insertAdmin("colla");
    pollId = UUID.randomUUID();
    // Only the columns PollRepositoryImpl.insert writes — SLUG_LOWER is a generated column,
    // so setting it here would fail.
    dsl.insertInto(POLLS)
        .set(POLLS.ID, pollId)
        .set(POLLS.OWNER_USERNAME, "owner")
        .set(POLLS.TITLE, "Test Poll")
        .set(POLLS.SLUG, "test-poll")
        .set(POLLS.CREATED_AT, OffsetDateTime.now())
        .set(POLLS.UPDATED_AT, OffsetDateTime.now())
        .execute();
  }

  private void insertAdmin(String u) {
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, u)
        .set(ADMIN_USER.PASSWORD_HASH, "x")
        .execute();
  }

  private UUID insertToken(String mintedBy) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(DECK_TOKENS)
        .set(DECK_TOKENS.ID, id)
        .set(DECK_TOKENS.POLL_ID, pollId)
        .set(DECK_TOKENS.TOKEN_HASH, "h-" + id)
        .set(DECK_TOKENS.MINTED_BY, mintedBy)
        .set(DECK_TOKENS.CREATED_AT, OffsetDateTime.now())
        .execute();
    return id;
  }

  private boolean revoked(UUID id) {
    return dsl.select(DECK_TOKENS.REVOKED_AT)
            .from(DECK_TOKENS)
            .where(DECK_TOKENS.ID.eq(id))
            .fetchOne(DECK_TOKENS.REVOKED_AT)
        != null;
  }

  @Test
  void revokeByPollAndMinter_revokesOnlyThatMinter() {
    UUID ownerTok = insertToken("owner");
    UUID collaTok = insertToken("colla");

    repository.revokeByPollAndMinter(pollId, "colla");

    assertThat(revoked(collaTok)).isTrue();
    assertThat(revoked(ownerTok)).isFalse();
  }

  @Test
  void revokeAllByMinter_revokesEverywhere() {
    UUID collaTok = insertToken("colla");
    repository.revokeAllByMinter("colla");
    assertThat(revoked(collaTok)).isTrue();
  }
}
