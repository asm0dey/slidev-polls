package site.asm0dey.slidev.polls.core.service;

/**
 * Validated input for AdminUserService.createInitialAdmin / createAdmin. Validation runs in the
 * compact constructor so controllers can pass the user's body through without writing duplicate
 * guards.
 */
public record CreateAdminCommand(String username, String password, String displayName) {

  private static final java.util.regex.Pattern USERNAME_PATTERN =
      java.util.regex.Pattern.compile("^[a-z0-9_-]{3,64}$");

  public CreateAdminCommand {
    if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
      throw new IllegalArgumentException(
          "username must match ^[a-z0-9_-]{3,64}$ (lowercase, no spaces)");
    }
    if (password == null || password.length() < 12) {
      throw new IllegalArgumentException("password must be at least 12 characters");
    }
    if (displayName == null) {
      throw new IllegalArgumentException("displayName must not be null");
    }
    String trimmed = displayName.strip();
    if (trimmed.isEmpty() || trimmed.length() > 100) {
      throw new IllegalArgumentException("displayName must be 1-100 characters");
    }
    displayName = trimmed;
  }
}
