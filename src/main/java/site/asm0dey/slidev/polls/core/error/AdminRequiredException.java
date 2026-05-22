package site.asm0dey.slidev.polls.core.error;

/** Thrown when a non-bootstrap-admin attempts an admin-only action (reset, create user, block). */
public class AdminRequiredException extends RuntimeException {
  public AdminRequiredException() {
    super("this action requires the administrator account");
  }
}
