package site.asm0dey.slidev.polls.persistence;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.DECK_TOKENS;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import site.asm0dey.slidev.polls.core.domain.DeckToken;
import site.asm0dey.slidev.polls.core.service.DeckTokenRepository;

/**
 * jOOQ-backed implementation of {@link DeckTokenRepository}. Only the SHA-256 hash lands in
 * storage; the plaintext is returned by {@link DeckTokenService#mint} once and discarded.
 */
@Repository
public class DeckTokenRepositoryImpl implements DeckTokenRepository {

  private final DSLContext dsl;

  public DeckTokenRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public DeckToken insert(DeckToken token) {
    OffsetDateTime createdAt =
        token.createdAt() != null
            ? OffsetDateTime.ofInstant(token.createdAt(), ZoneOffset.UTC)
            : OffsetDateTime.now(ZoneOffset.UTC);
    dsl.insertInto(DECK_TOKENS)
        .set(DECK_TOKENS.ID, token.id())
        .set(DECK_TOKENS.POLL_ID, token.pollId())
        .set(DECK_TOKENS.TOKEN_HASH, token.tokenHash())
        .set(DECK_TOKENS.LABEL, token.label())
        .set(DECK_TOKENS.CREATED_AT, createdAt)
        .execute();
    return new DeckToken(
        token.id(), token.pollId(), token.tokenHash(), token.label(), createdAt.toInstant(), null);
  }

  @Override
  public List<DeckToken> findByPoll(UUID pollId) {
    return dsl.selectFrom(DECK_TOKENS)
        .where(DECK_TOKENS.POLL_ID.eq(pollId))
        .orderBy(DECK_TOKENS.CREATED_AT.asc())
        .fetch()
        .map(DeckTokenRepositoryImpl::toDomain);
  }

  @Override
  public Optional<DeckToken> findById(UUID tokenId) {
    return dsl.selectFrom(DECK_TOKENS)
        .where(DECK_TOKENS.ID.eq(tokenId))
        .fetchOptional()
        .map(DeckTokenRepositoryImpl::toDomain);
  }

  @Override
  public Optional<DeckToken> findLiveByHash(String tokenHash) {
    return dsl.selectFrom(DECK_TOKENS)
        .where(DECK_TOKENS.TOKEN_HASH.eq(tokenHash).and(DECK_TOKENS.REVOKED_AT.isNull()))
        .fetchOptional()
        .map(DeckTokenRepositoryImpl::toDomain);
  }

  @Override
  public DeckToken revoke(UUID tokenId) {
    OffsetDateTime revokedAt = OffsetDateTime.now(ZoneOffset.UTC);
    dsl.update(DECK_TOKENS)
        .set(DECK_TOKENS.REVOKED_AT, revokedAt)
        .where(DECK_TOKENS.ID.eq(tokenId).and(DECK_TOKENS.REVOKED_AT.isNull()))
        .execute();
    return dsl.selectFrom(DECK_TOKENS)
        .where(DECK_TOKENS.ID.eq(tokenId))
        .fetchOptional()
        .map(DeckTokenRepositoryImpl::toDomain)
        .orElseThrow(() -> new IllegalStateException("deck token " + tokenId + " vanished"));
  }

  private static DeckToken toDomain(Record record) {
    return new DeckToken(
        record.get(DECK_TOKENS.ID),
        record.get(DECK_TOKENS.POLL_ID),
        record.get(DECK_TOKENS.TOKEN_HASH),
        record.get(DECK_TOKENS.LABEL),
        record.get(DECK_TOKENS.CREATED_AT).toInstant(),
        record.get(DECK_TOKENS.REVOKED_AT) == null
            ? null
            : record.get(DECK_TOKENS.REVOKED_AT).toInstant());
  }
}
