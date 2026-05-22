package site.asm0dey.slidev.polls.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import site.asm0dey.slidev.polls.persistence.h2.TouchPollUpdatedAtTrigger;

/**
 * GraalVM native-image reflection registration. The quarkiverse {@code quarkus-jooq} extension was
 * dropped (see pom.xml), so the reflection metadata jOOQ relies on at runtime is registered here
 * manually instead.
 *
 * <p><b>jOOQ generated classes</b> — jOOQ instantiates {@code Record} subclasses reflectively
 * (no-arg constructor) and reads {@code Table}/{@code Schema}/{@code Catalog} singletons via their
 * declared fields, so every generated class in {@code persistence.jooq.*} must keep its members.
 *
 * <p><b>BouncyCastle Argon2</b> — {@link
 * site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher} uses {@code
 * Argon2BytesGenerator}/{@code Argon2Parameters} directly; registered defensively so the native
 * image keeps their members reachable.
 *
 * <p><b>H2 trigger</b> — {@link TouchPollUpdatedAtTrigger} is loaded by fully-qualified name from
 * the H2 migration {@code V2__derive_poll_state.sql} ({@code CALL "...TouchPollUpdatedAtTrigger"}),
 * which H2 instantiates via its no-arg constructor. Only relevant when H2 is in the native image.
 */
@RegisterForReflection(
    targets = {
      // jOOQ generated metamodel + records (persistence.jooq.*)
      site.asm0dey.slidev.polls.persistence.jooq.DefaultCatalog.class,
      site.asm0dey.slidev.polls.persistence.jooq.DefaultSchema.class,
      site.asm0dey.slidev.polls.persistence.jooq.Indexes.class,
      site.asm0dey.slidev.polls.persistence.jooq.Keys.class,
      site.asm0dey.slidev.polls.persistence.jooq.Tables.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.AdminUser.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.DeckTokens.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.PollAllowedOrigins.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.PollOptions.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.PollQuestions.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.Polls.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.Votes.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.records.AdminUserRecord.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.records.DeckTokensRecord.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.records.PollAllowedOriginsRecord.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.records.PollOptionsRecord.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.records.PollQuestionsRecord.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.records.PollsRecord.class,
      site.asm0dey.slidev.polls.persistence.jooq.tables.records.VotesRecord.class,
      // BouncyCastle Argon2 (Argon2PasswordHasher)
      org.bouncycastle.crypto.generators.Argon2BytesGenerator.class,
      org.bouncycastle.crypto.params.Argon2Parameters.class,
      // H2 trigger loaded by name from the H2 migration
      TouchPollUpdatedAtTrigger.class,
      // Problem envelope is serialised by Jackson OUTSIDE the REST layer — directly via
      // ObjectMapper in ProblemChallengeWriter (auth 401/403 challenge) and AdminCsrfFilter — so
      // Quarkus' automatic REST-type registration doesn't cover it. Without these the native image
      // serialises an empty {} instead of {"code":"AUTH_REQUIRED",...}.
      site.asm0dey.slidev.polls.api.error.Problem.class,
      site.asm0dey.slidev.polls.api.error.ProblemCode.class,
    })
public final class NativeReflectionConfig {
  private NativeReflectionConfig() {}
}
