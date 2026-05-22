package site.asm0dey.slidev.polls.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import java.time.Instant;
import java.util.List;
import org.jooq.exception.DataAccessException;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.core.domain.AdminUser;
import site.asm0dey.slidev.polls.core.error.SetupLockedException;
import site.asm0dey.slidev.polls.core.error.UsernameTakenException;

/**
 * Manages presenter accounts. The first account is created via {@link #createInitialAdmin} which is
 * gated on an empty table; subsequent accounts go through {@link #createAdmin} which only requires
 * the caller to be authenticated (the controller enforces that, not this class).
 */
@ApplicationScoped
public class AdminUserService {

  private final AdminUserRepository repository;
  private final Argon2PasswordHasher passwordHasher;
  private final UserTransaction userTransaction;

  public AdminUserService(
      AdminUserRepository repository,
      Argon2PasswordHasher passwordHasher,
      UserTransaction userTransaction) {
    this.repository = repository;
    this.passwordHasher = passwordHasher;
    this.userTransaction = userTransaction;
  }

  public boolean isSetupRequired() {
    return repository.count() == 0L;
  }

  /**
   * Inserts the bootstrap presenter inside a programmatic JTA transaction (a {@link
   * UserTransaction}, not {@code @Transactional}) so the catch sits OUTSIDE the transaction
   * boundary — Postgres reports the serialization conflict at COMMIT time, which a try/catch inside
   * an {@code @Transactional} method body cannot observe. Two concurrent setup attempts therefore
   * resolve as:
   *
   * <ul>
   *   <li>both pass the in-tx {@code count()} check at their MVCC snapshot,
   *   <li>both insert,
   *   <li>one commit succeeds, the other fails (a {@link DataAccessException} on the username PK,
   *       or a serialization conflict surfaced as a JTA rollback at {@code commit()}).
   * </ul>
   *
   * The catch translates either failure into {@link SetupLockedException} so the controller surface
   * is consistently {@code 409 Problem(SETUP_LOCKED)} (FR-017 / Principle VI: actionable failure
   * categories, no leaking of database-level error wording).
   *
   * <p>NOTE: SERIALIZABLE isolation can no longer be requested per-transaction the way Spring's
   * {@code TransactionTemplate} did — JTA {@link UserTransaction} has no isolation knob. The
   * isolation level must be set at the datasource/connection level (see the Quarkus datasource
   * config). If the datasource default is READ COMMITTED, the username PK is still the backstop,
   * but the "both insert, one loses at COMMIT on serialization conflict" path degrades to a
   * PK-collision path only.
   */
  public AdminUser createInitialAdmin(CreateAdminCommand command) {
    try {
      userTransaction.begin();
    } catch (Exception ex) {
      throw new IllegalStateException("could not begin setup transaction", ex);
    }
    try {
      if (repository.count() != 0L) {
        throw new SetupLockedException("setup already complete");
      }
      String hash = passwordHasher.encode(command.password());
      repository.insert(command.username(), hash);
      AdminUser created = new AdminUser(command.username(), Instant.now());
      userTransaction.commit();
      return created;
    } catch (SetupLockedException ex) {
      rollbackQuietly();
      throw ex;
    } catch (DataAccessException ex) {
      // Lost the insert race: a concurrent setup committed first and the username PK (or the
      // serialization conflict surfaced at COMMIT) rejected this attempt. Translate to the
      // controller-facing 409 SETUP_LOCKED rather than leaking the database error wording.
      rollbackQuietly();
      throw new SetupLockedException("setup already complete");
    } catch (Exception ex) {
      // COMMIT-time serialization failure also lands here as a generic JTA RollbackException.
      rollbackQuietly();
      throw new SetupLockedException("setup already complete");
    }
  }

  private void rollbackQuietly() {
    try {
      userTransaction.rollback();
    } catch (Exception ignored) {
      // Best-effort rollback: the transaction may already be rolled back by the manager.
    }
  }

  /**
   * Adds an additional presenter on behalf of an authenticated admin. The pre-check on {@link
   * AdminUserRepository#existsByUsername(String)} is a check-then-act under READ COMMITTED, so the
   * primary key on {@code admin_user.username} is what actually serialises concurrent inserts. The
   * {@link DataAccessException} catch translates the late-detected collision into the same {@link
   * UsernameTakenException} the pre-check would have thrown — the controller contract stays {@code
   * 409 USERNAME_TAKEN} regardless of which thread won the race.
   */
  @Transactional
  public AdminUser createAdmin(CreateAdminCommand command) {
    if (repository.existsByUsername(command.username())) {
      throw new UsernameTakenException(command.username());
    }
    String hash = passwordHasher.encode(command.password());
    try {
      repository.insert(command.username(), hash);
    } catch (DataAccessException _) {
      throw new UsernameTakenException(command.username());
    }
    return new AdminUser(command.username(), Instant.now());
  }

  public List<AdminUser> listAdmins() {
    return repository.listAll();
  }
}
