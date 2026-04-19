package site.asm0dey.slidev.polls.api.security;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import java.util.Collections;
import org.jooq.DSLContext;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Presenter-login source of truth. Backed by the {@code admin_user} table — seeded with "alice" by
 * {@code V3__admin_user.sql} — the BCrypt hash stored there is compared by Spring Security's {@code
 * BCryptPasswordEncoder} wired in {@link SecurityConfig}, so per-install pgcrypto salts validate
 * against the same literal password.
 *
 * <p>Usernames are persisted lowercase (CHECK constraint in V3), so we lowercase the incoming
 * lookup to keep the login form case-insensitive. The {@code AUTHENTICATED} authority is the sole
 * role in v1 — ownership is enforced by the poll-core service layer via the authenticated
 * principal's name, not via role-based checks.
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

  static final String ROLE_AUTHENTICATED = "ROLE_AUTHENTICATED";

  private final DSLContext dsl;

  public AdminUserDetailsService(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    if (username == null || username.isBlank()) {
      throw new UsernameNotFoundException("empty username");
    }
    String lookup = username.toLowerCase();
    var row =
        dsl.select(ADMIN_USER.USERNAME, ADMIN_USER.BCRYPT_HASH)
            .from(ADMIN_USER)
            .where(ADMIN_USER.USERNAME.eq(lookup))
            .fetchOne();
    if (row == null) {
      throw new UsernameNotFoundException("no such presenter: " + lookup);
    }
    return User.withUsername(row.get(ADMIN_USER.USERNAME))
        .password(row.get(ADMIN_USER.BCRYPT_HASH))
        .authorities(Collections.singletonList(() -> ROLE_AUTHENTICATED))
        .build();
  }
}
