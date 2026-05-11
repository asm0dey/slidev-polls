package site.asm0dey.slidev.polls.core.service;

/**
 * Validated input for AdminUserService.createInitialAdmin / createAdmin. Validation runs in the
 * compact constructor so controllers can pass the user's body through without writing duplicate
 * guards.
 */
public record CreateAdminCommand(String username, String password, String displayName) {

  // Accept any-case input from the user; we normalise to lowercase before
  // storage so the admin_user.username CHECK constraint (lower(username))
  // and the case-insensitive login flow keep their invariants. "Alice" and
  // "alice" therefore refer to the same account, which is the behaviour
  // most operators expect.
  private static final java.util.regex.Pattern USERNAME_PATTERN =
      java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{3,64}$");

  public CreateAdminCommand {
    if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
      throw new IllegalArgumentException(
          "username must match ^[a-zA-Z0-9_-]{3,64}$ (letters, digits, underscore, hyphen)");
    }
    username = username.toLowerCase(java.util.Locale.ROOT);
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

  /**
   * Records auto-generate {@code toString()} from every component, which would dump the plaintext
   * password into any log line that captured the command (Spring Boot DEBUG of validation errors,
   * exception messages built with concatenation, etc.). Override to mask.
   */
  @Override
  public String toString() {
    return "CreateAdminCommand[username="
        + username
        + ", password=***, displayName="
        + displayName
        + "]";
  }
}
