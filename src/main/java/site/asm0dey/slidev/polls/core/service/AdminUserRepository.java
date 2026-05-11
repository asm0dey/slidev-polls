package site.asm0dey.slidev.polls.core.service;

import java.util.List;
import java.util.Optional;
import site.asm0dey.slidev.polls.core.domain.AdminUser;

/**
 * Persistence boundary for the admin_user table. The hash never crosses the boundary in either
 * direction except via {@link #findPasswordHash(String)} (used by the UserDetailsService) and
 * {@link #insert(String, String, String)}.
 */
public interface AdminUserRepository {
  long count();

  boolean existsByUsername(String username);

  void insert(String username, String passwordHash, String displayName);

  List<AdminUser> listAll();

  Optional<String> findPasswordHash(String username);
}
