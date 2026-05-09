package site.asm0dey.slidev.polls.core.service;

import java.time.Instant;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import site.asm0dey.slidev.polls.core.domain.AdminUser;
import site.asm0dey.slidev.polls.core.error.SetupLockedException;
import site.asm0dey.slidev.polls.core.error.UsernameTakenException;

/**
 * Manages presenter accounts. The first account is created via {@link #createInitialAdmin} which is
 * gated on an empty table; subsequent accounts go through {@link #createAdmin} which only requires
 * the caller to be authenticated (the controller enforces that, not this class).
 */
@Service
public class AdminUserService {

  private final AdminUserRepository repository;
  private final PasswordEncoder passwordEncoder;

  public AdminUserService(AdminUserRepository repository, PasswordEncoder passwordEncoder) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
  }

  public boolean isSetupRequired() {
    return repository.count() == 0L;
  }

  /**
   * Inserts the bootstrap presenter inside a SERIALIZABLE transaction. Two concurrent callers can
   * both observe count==0 at MVCC snapshot time, but only one transaction commits — the other gets
   * a serialization failure that we translate to {@link SetupLockedException} on the next count()
   * check. The recheck inside the same transaction makes the invariant explicit.
   */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public AdminUser createInitialAdmin(CreateAdminCommand command) {
    if (repository.count() != 0L) {
      throw new SetupLockedException("setup already complete");
    }
    String hash = passwordEncoder.encode(command.password());
    repository.insert(command.username(), hash, command.displayName());
    return new AdminUser(command.username(), command.displayName(), Instant.now());
  }

  @Transactional
  public AdminUser createAdmin(CreateAdminCommand command) {
    if (repository.existsByUsername(command.username())) {
      throw new UsernameTakenException(command.username());
    }
    String hash = passwordEncoder.encode(command.password());
    repository.insert(command.username(), hash, command.displayName());
    return new AdminUser(command.username(), command.displayName(), Instant.now());
  }

  public List<AdminUser> listAdmins() {
    return repository.listAll();
  }
}
