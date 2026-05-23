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
import site.asm0dey.slidev.polls.core.error.CannotBlockException;
import site.asm0dey.slidev.polls.core.error.CurrentPasswordMismatchException;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.error.SetupLockedException;
import site.asm0dey.slidev.polls.core.error.UsernameTakenException;

/**
 * Manages presenter accounts. The first account is created via {@link #createInitialAdmin} which is
 * gated on an empty table; subsequent accounts go through {@link #createAdmin} which requires
 * bootstrap-admin authority (the controller enforces that via its admin gate, not this class).
 */
@Service
public class AdminUserService {

  private final AdminUserRepository repository;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate serializableTx;
  private final DeckTokenRepository deckTokenRepository;

  public AdminUserService(
      AdminUserRepository repository,
      PasswordEncoder passwordEncoder,
      PlatformTransactionManager transactionManager,
      DeckTokenRepository deckTokenRepository) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
    this.serializableTx = new TransactionTemplate(transactionManager);
    this.serializableTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    this.deckTokenRepository = deckTokenRepository;
  }

  public boolean isSetupRequired() {
    return repository.count() == 0L;
  }

  /**
   * True when {@code username} is the bootstrap admin — the earliest-created account (tie-broken by
   * username). This is the implicit privilege source for user creation, password reset, and
   * block/unblock until an explicit role model exists.
   */
  public boolean isBootstrapAdmin(String username) {
    return repository.findBootstrapAdminUsername().map(b -> b.equals(username)).orElse(false);
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

  /**
   * Self-service change. Verifies {@code currentPassword} against the stored Argon2 hash, then
   * re-encodes {@code newPassword}. Does not touch sessions — the controller revokes the user's
   * other sessions after this returns.
   *
   * @throws site.asm0dey.slidev.polls.core.error.NotFoundException if the user does not exist
   * @throws site.asm0dey.slidev.polls.core.error.CurrentPasswordMismatchException on a wrong
   *     current
   */
  @Transactional
  public void changeOwnPassword(String username, String currentPassword, String newPassword) {
    String hash =
        repository.findPasswordHash(username).orElseThrow(() -> new NotFoundException(username));
    if (!passwordEncoder.matches(currentPassword, hash)) {
      throw new CurrentPasswordMismatchException();
    }
    repository.updatePasswordHash(username, passwordEncoder.encode(newPassword));
  }

  /**
   * Admin reset. No current-password check — caller authorization (bootstrap admin) is enforced by
   * the controller. The controller revokes all of the target's sessions after this returns.
   *
   * @throws site.asm0dey.slidev.polls.core.error.NotFoundException if the target does not exist
   */
  @Transactional
  public void resetPassword(String targetUsername, String newPassword) {
    if (repository.findPasswordHash(targetUsername).isEmpty()) {
      throw new NotFoundException(targetUsername);
    }
    repository.updatePasswordHash(targetUsername, passwordEncoder.encode(newPassword));
  }

  public List<AdminUser> listAdmins() {
    return repository.listAll();
  }

  /**
   * Block {@code rawTarget}. Guards: the caller cannot block themselves, and the bootstrap admin
   * cannot be blocked (lockout protection). Sets {@code blocked_at} and revokes every deck token
   * the target minted. The controller separately expires the target's sessions.
   */
  @Transactional
  public void block(String caller, String rawTarget) {
    String target = Usernames.normalize(rawTarget);
    if (target.equals(caller)) {
      throw new CannotBlockException("cannot block yourself");
    }
    if (repository.findBootstrapAdminUsername().map(target::equals).orElse(false)) {
      throw new CannotBlockException("cannot block the administrator");
    }
    if (repository.findPasswordHash(target).isEmpty()) {
      throw new NotFoundException(target);
    }
    repository.setBlockedAt(target, Instant.now());
    deckTokenRepository.revokeAllByMinter(target);
  }

  /** Unblock {@code rawTarget}. Restores login only; revoked tokens stay revoked. */
  @Transactional
  public void unblock(String rawTarget) {
    String target = Usernames.normalize(rawTarget);
    if (repository.findPasswordHash(target).isEmpty()) {
      throw new NotFoundException(target);
    }
    repository.setBlockedAt(target, null);
  }
}
