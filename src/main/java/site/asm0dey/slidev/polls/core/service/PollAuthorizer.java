package site.asm0dey.slidev.polls.core.service;

import org.springframework.stereotype.Component;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.error.NotOwnerException;

/**
 * Single definition of poll authorization. {@code owner} = the poll's {@code ownerUsername}; {@code
 * editor} = owner or a row in {@code poll_collaborators}. Owner-reserved actions (delete, transfer,
 * collaborator management) use {@link #requireOwner}; all other mutations and reads use {@link
 * #requireEditor}. Both failures throw {@link NotOwnerException} (HTTP 403).
 */
@Component
public class PollAuthorizer {

  private final PollCollaboratorRepository collaborators;

  public PollAuthorizer(PollCollaboratorRepository collaborators) {
    this.collaborators = collaborators;
  }

  public boolean isOwner(Poll poll, String username) {
    return poll.ownerUsername().equals(username);
  }

  public boolean isEditor(Poll poll, String username) {
    return isOwner(poll, username) || collaborators.exists(poll.id(), username);
  }

  public void requireOwner(Poll poll, String username) {
    if (!isOwner(poll, username)) {
      throw new NotOwnerException("poll " + poll.id() + " is not owned by " + username);
    }
  }

  public void requireEditor(Poll poll, String username) {
    if (!isEditor(poll, username)) {
      throw new NotOwnerException("poll " + poll.id() + " is not editable by " + username);
    }
  }
}
