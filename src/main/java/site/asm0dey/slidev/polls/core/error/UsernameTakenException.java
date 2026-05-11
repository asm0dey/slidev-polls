package site.asm0dey.slidev.polls.core.error;

/** Thrown by AdminUserService.createAdmin when the requested username already exists. */
public class UsernameTakenException extends RuntimeException {
  public UsernameTakenException(String username) {
    super("username already taken: " + username);
  }
}
