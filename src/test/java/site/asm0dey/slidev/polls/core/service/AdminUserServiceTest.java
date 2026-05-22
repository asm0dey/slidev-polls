package site.asm0dey.slidev.polls.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.transaction.RollbackException;
import jakarta.transaction.UserTransaction;
import java.time.Instant;
import java.util.List;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.slidev.polls.api.security.Argon2PasswordHasher;
import site.asm0dey.slidev.polls.core.domain.AdminUser;
import site.asm0dey.slidev.polls.core.error.SetupLockedException;
import site.asm0dey.slidev.polls.core.error.UsernameTakenException;

/**
 * Pure-Java (Mockito) unit coverage for {@link AdminUserService}, ported off Spring.
 *
 * <p>C3 — the setup-race contract no longer rides on SERIALIZABLE isolation. Under JTA there is no
 * per-transaction isolation knob ({@link UserTransaction} has none), so the race is resolved by the
 * {@code admin_user.username} unique constraint: when a concurrent setup commits first, the loser's
 * {@code insert} surfaces a jOOQ {@link DataAccessException}, which the service translates into the
 * same {@link SetupLockedException} ({@code 409 SETUP_LOCKED}) the in-tx {@code count()} pre-check
 * would have produced. A failure observed at {@code commit()} time (a JTA {@link
 * RollbackException}, e.g. a serialization conflict surfaced if the datasource is configured
 * SERIALIZABLE) lands in the service's generic catch and translates to the same {@code
 * SETUP_LOCKED}.
 *
 * <p>The {@link UserTransaction} is a pass-through mock: {@code begin}/{@code commit}/{@code
 * rollback} are no-ops so the lambda body runs as if inside a transaction, and individual tests
 * make {@code commit()} or {@code insert} throw to drive the loser path.
 */
class AdminUserServiceTest {

  AdminUserRepository repo;
  Argon2PasswordHasher encoder;
  UserTransaction userTransaction;
  AdminUserService service;

  @BeforeEach
  void setUp() {
    repo = mock(AdminUserRepository.class);
    encoder = mock(Argon2PasswordHasher.class);
    userTransaction = mock(UserTransaction.class);
    when(encoder.encode("password-twelve")).thenReturn("$argon2id$encoded");
    service = new AdminUserService(repo, encoder, userTransaction);
  }

  @Test
  void isSetupRequiredTrueWhenTableEmpty() {
    when(repo.count()).thenReturn(0L);
    assertThat(service.isSetupRequired()).isTrue();
  }

  @Test
  void isSetupRequiredFalseWhenAnyUserExists() {
    when(repo.count()).thenReturn(1L);
    assertThat(service.isSetupRequired()).isFalse();
  }

  @Test
  void createInitialAdminInsertsWhenTableEmpty() {
    when(repo.count()).thenReturn(0L);

    AdminUser created =
        service.createInitialAdmin(new CreateAdminCommand("alice", "password-twelve"));

    verify(encoder, times(1)).encode("password-twelve");
    verify(repo).insert("alice", "$argon2id$encoded");
    assertThat(created.username()).isEqualTo("alice");
  }

  @Test
  void createInitialAdminThrowsWhenTableAlreadyPopulated() {
    when(repo.count()).thenReturn(1L);

    assertThatThrownBy(
            () -> service.createInitialAdmin(new CreateAdminCommand("alice", "password-twelve")))
        .isInstanceOf(SetupLockedException.class);

    verify(repo, never()).insert(anyString(), anyString());
  }

  @Test
  void createAdminThrowsWhenUsernameTaken() {
    when(repo.existsByUsername("alice")).thenReturn(true);

    assertThatThrownBy(
            () -> service.createAdmin(new CreateAdminCommand("alice", "password-twelve")))
        .isInstanceOf(UsernameTakenException.class);
  }

  @Test
  void createAdminInsertsWhenUsernameFree() {
    when(repo.existsByUsername("bob")).thenReturn(false);
    when(encoder.encode("password-twelve")).thenReturn("$argon2id$encoded");

    service.createAdmin(new CreateAdminCommand("bob", "password-twelve"));

    verify(repo).insert("bob", "$argon2id$encoded");
  }

  @Test
  void listAdminsDelegatesToRepository() {
    var bob = new AdminUser("bob", Instant.parse("2026-05-09T00:00:00Z"));
    when(repo.listAll()).thenReturn(List.of(bob));

    assertThat(service.listAdmins()).containsExactly(bob);
  }

  @Test
  void rejectsBlankUsername() {
    assertThatThrownBy(
            () -> service.createInitialAdmin(new CreateAdminCommand("", "password-twelve")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("username");
  }

  @Test
  void rejectsShortPassword() {
    assertThatThrownBy(() -> service.createInitialAdmin(new CreateAdminCommand("alice", "short")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("password");
  }

  @Test
  void normalisesMixedCaseUsernameToLowercaseBeforeInsert() {
    when(repo.count()).thenReturn(0L);

    service.createInitialAdmin(new CreateAdminCommand("Alice", "password-twelve"));

    // Stored username is lowercase to match the admin_user.username CHECK constraint and the
    // case-insensitive login flow. Operators may type "Alice" or "ALICE"; both resolve to "alice".
    verify(repo).insert("alice", "$argon2id$encoded");
  }

  // C3 — a failure surfaced at COMMIT time (a JTA RollbackException, the JTA analogue of the old
  // SERIALIZABLE-conflict-at-commit Spring path) lands in the service's generic catch and is
  // translated to SETUP_LOCKED. Under JTA the isolation level is set on the datasource, not
  // per-transaction, so the test pins the commit-time-failure → SETUP_LOCKED contract directly.
  @Test
  void createInitialAdminTranslatesCommitFailureToSetupLocked() throws Exception {
    when(repo.count()).thenReturn(0L);
    doThrow(new RollbackException("could not serialize access")).when(userTransaction).commit();

    assertThatThrownBy(
            () -> service.createInitialAdmin(new CreateAdminCommand("alice", "password-twelve")))
        .isInstanceOf(SetupLockedException.class);
  }

  // C3 — two setups racing with the SAME username: the loser's insert hits the username unique
  // constraint before commit. jOOQ surfaces that as a DataAccessException, which the service
  // translates to SETUP_LOCKED (the unique-constraint backstop replacing the SERIALIZABLE path).
  @Test
  void createInitialAdminTranslatesUniqueViolationToSetupLocked() {
    when(repo.count()).thenReturn(0L);
    doThrow(new DataAccessException("duplicate key value violates unique constraint"))
        .when(repo)
        .insert(anyString(), anyString());

    assertThatThrownBy(
            () -> service.createInitialAdmin(new CreateAdminCommand("alice", "password-twelve")))
        .isInstanceOf(SetupLockedException.class);
  }

  // The check passes (existsByUsername returns false), but a concurrent insert races us to the
  // username unique constraint. The service must translate the late-detected jOOQ
  // DataAccessException into the same 409 UsernameTakenException the pre-check would have produced.
  @Test
  void createAdminTranslatesUniqueViolationToUsernameTaken() {
    when(repo.existsByUsername("bob")).thenReturn(false);
    doThrow(new DataAccessException("duplicate key value violates unique constraint"))
        .when(repo)
        .insert(anyString(), anyString());

    assertThatThrownBy(() -> service.createAdmin(new CreateAdminCommand("bob", "password-twelve")))
        .isInstanceOf(UsernameTakenException.class);
  }
}
