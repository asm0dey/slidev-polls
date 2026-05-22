package site.asm0dey.slidev.polls.core.error;

/** Thrown by self-service password change when the supplied current password does not match. */
public class CurrentPasswordMismatchException extends RuntimeException {
  public CurrentPasswordMismatchException() {
    super("current password is incorrect");
  }
}
