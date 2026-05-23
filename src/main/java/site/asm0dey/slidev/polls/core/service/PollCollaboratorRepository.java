package site.asm0dey.slidev.polls.core.service;

import java.util.List;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.PollCollaborator;

/** Persistence boundary for the {@code poll_collaborators} table. */
public interface PollCollaboratorRepository {

  /**
   * Inserts a collaborator row and returns it. Caller guarantees the user exists and isn't owner.
   */
  PollCollaborator add(UUID pollId, String username);

  /** Idempotent: removes the row if present. */
  void remove(UUID pollId, String username);

  boolean exists(UUID pollId, String username);

  List<PollCollaborator> listByPoll(UUID pollId);
}
