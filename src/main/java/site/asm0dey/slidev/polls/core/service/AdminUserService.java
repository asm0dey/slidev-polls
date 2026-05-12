package site.asm0dey.slidev.polls.core.service;

import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
  private final TransactionTemplate serializableTx;

  public AdminUserService(
      AdminUserRepository repository,
      PasswordEncoder passwordEncoder,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
    this.serializableTx = new TransactionTemplate(transactionManager);
    this.serializableTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
  }

  public boolean isSetupRequired() {
    return repository.count() == 0L;
  }

  /**
   * Inserts the bootstrap presenter inside a SERIALIZABLE transaction. We use a programmatic
   * TransactionTemplate (not @Transactional) so the catch sits OUTSIDE the transaction boundary —
   * Postgres reports the serialization conflict at COMMIT time, which a try/catch inside
   * an @Transactional method body cannot observe. Two concurrent setup attempts therefore resolve
   * as:
   *
   * <ul>
   *   <li>both pass the in-tx {@code count()} check at their MVCC snapshot,
   *   <li>both insert,
   *   <li>one commit succeeds, the other fails with {@link PessimisticLockingFailureException} (or
   *       {@link DataIntegrityViolationException} if usernames collide on the PK).
   * </ul>
   *
   * The catch translates either failure into {@link SetupLockedException} so the controller surface
   * is consistently {@code 409 Problem(SETUP_LOCKED)} (FR-017 / Principle VI: actionable failure
   * categories, no leaking of database-level error wording).
   */
  public AdminUser createInitialAdmin(CreateAdminCommand command) {
    try {
      return serializableTx.execute(
          _ -> {
            if (repository.count() != 0L) {
              throw new SetupLockedException("setup already complete");
            }
            String hash = passwordEncoder.encode(command.password());
            repository.insert(command.username(), hash);
            return new AdminUser(command.username(), Instant.now());
          });
    } catch (PessimisticLockingFailureException | DataIntegrityViolationException _) {
      throw new SetupLockedException("setup already complete");
    }
  }

  /**
   * Adds an additional presenter on behalf of an authenticated admin. The pre-check on {@link
   * AdminUserRepository#existsByUsername(String)} is a check-then-act under READ COMMITTED, so the
   * primary key on {@code admin_user.username} is what actually serialises concurrent inserts. The
   * {@link DataIntegrityViolationException} catch translates the late-detected collision into the
   * same {@link UsernameTakenException} the pre-check would have thrown — the controller contract
   * stays {@code 409 USERNAME_TAKEN} regardless of which thread won the race.
   */
  @Transactional
  public AdminUser createAdmin(CreateAdminCommand command) {
    if (repository.existsByUsername(command.username())) {
      throw new UsernameTakenException(command.username());
    }
    String hash = passwordEncoder.encode(command.password());
    try {
      repository.insert(command.username(), hash);
    } catch (DataIntegrityViolationException _) {
      throw new UsernameTakenException(command.username());
    }
    return new AdminUser(command.username(), Instant.now());
  }

  public List<AdminUser> listAdmins() {
    return repository.listAll();
  }
}
