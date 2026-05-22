package site.asm0dey.slidev.polls.core.error;

/** Thrown when adding a collaborator who is already one. */
public class CollaboratorExistsException extends RuntimeException {
  public CollaboratorExistsException(String username) {
    super("already a collaborator: " + username);
  }
}
