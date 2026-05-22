package site.asm0dey.slidev.polls.core.error;

/** Thrown when adding the poll's own owner as a collaborator. */
public class CannotShareWithOwnerException extends RuntimeException {
  public CannotShareWithOwnerException(String username) {
    super("cannot add the owner as a collaborator: " + username);
  }
}
