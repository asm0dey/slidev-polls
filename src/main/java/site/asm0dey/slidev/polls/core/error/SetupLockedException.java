package site.asm0dey.slidev.polls.core.error;

/** Thrown by AdminUserService.createInitialAdmin once admin_user is non-empty. */
public class SetupLockedException extends RuntimeException {
  public SetupLockedException(String message) {
    super(message);
  }
}
