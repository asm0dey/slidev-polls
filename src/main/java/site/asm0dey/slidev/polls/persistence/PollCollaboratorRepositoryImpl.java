package site.asm0dey.slidev.polls.persistence;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLL_COLLABORATORS;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import site.asm0dey.slidev.polls.core.domain.PollCollaborator;
import site.asm0dey.slidev.polls.core.service.PollCollaboratorRepository;

@Repository
public class PollCollaboratorRepositoryImpl implements PollCollaboratorRepository {

  private final DSLContext dsl;

  public PollCollaboratorRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public PollCollaborator add(UUID pollId, String username) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    dsl.insertInto(POLL_COLLABORATORS)
        .set(POLL_COLLABORATORS.POLL_ID, pollId)
        .set(POLL_COLLABORATORS.USERNAME, username)
        .set(POLL_COLLABORATORS.CREATED_AT, now)
        .execute();
    return new PollCollaborator(username, now.toInstant());
  }

  @Override
  public void remove(UUID pollId, String username) {
    dsl.deleteFrom(POLL_COLLABORATORS)
        .where(POLL_COLLABORATORS.POLL_ID.eq(pollId))
        .and(POLL_COLLABORATORS.USERNAME.eq(username))
        .execute();
  }

  @Override
  public boolean exists(UUID pollId, String username) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(POLL_COLLABORATORS)
            .where(POLL_COLLABORATORS.POLL_ID.eq(pollId))
            .and(POLL_COLLABORATORS.USERNAME.eq(username)));
  }

  @Override
  public List<PollCollaborator> listByPoll(UUID pollId) {
    return dsl.select(POLL_COLLABORATORS.USERNAME, POLL_COLLABORATORS.CREATED_AT)
        .from(POLL_COLLABORATORS)
        .where(POLL_COLLABORATORS.POLL_ID.eq(pollId))
        .orderBy(POLL_COLLABORATORS.CREATED_AT.asc())
        .fetch(
            r ->
                new PollCollaborator(
                    r.get(POLL_COLLABORATORS.USERNAME),
                    r.get(POLL_COLLABORATORS.CREATED_AT).toInstant()));
  }
}
