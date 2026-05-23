package site.asm0dey.slidev.polls.core.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import site.asm0dey.slidev.polls.core.domain.AdminUser;

/**
 * Persistence boundary for the admin_user table. The hash never crosses the boundary in either
 * direction except via {@link #findPasswordHash(String)} (used by the UserDetailsService) and
 * {@link #insert(String, String)}.
 */
public interface AdminUserRepository {
  long count();

  boolean existsByUsername(String username);

  void insert(String username, String passwordHash);

  List<AdminUser> listAll();

  Optional<String> findPasswordHash(String username);

  Optional<String> findBootstrapAdminUsername();

  void updatePasswordHash(String username, String passwordHash);

  /** Sets or clears the block timestamp ({@code null} unblocks). */
  void setBlockedAt(String username, Instant blockedAt);

  /** Usernames whose {@code blocked_at} is non-null. */
  Set<String> listBlockedUsernames();
}
