package site.asm0dey.slidev.polls.core.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single normalization point for username inputs that arrive outside account creation (collaborator
 * add/remove, ownership transfer, password reset, block/unblock). Usernames are persisted lowercase
 * (admin_user CHECK lower(username)), so every lookup must lowercase first or it silently misses.
 */
public final class Usernames {

  private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,64}$");

  private Usernames() {}

  /**
   * Trims, validates against the canonical pattern, and lowercases.
   *
   * @throws IllegalArgumentException if null or not matching {@code ^[a-zA-Z0-9_-]{3,64}$}
   */
  public static String normalize(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("username must not be null");
    }
    String trimmed = raw.trim();
    if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(
          "username must match ^[a-zA-Z0-9_-]{3,64}$ (letters, digits, underscore, hyphen)");
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }
}
