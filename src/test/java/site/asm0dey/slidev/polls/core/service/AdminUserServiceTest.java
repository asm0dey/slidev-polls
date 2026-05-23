package site.asm0dey.slidev.polls.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import site.asm0dey.slidev.polls.core.domain.AdminUser;
import site.asm0dey.slidev.polls.core.error.SetupLockedException;
import site.asm0dey.slidev.polls.core.error.UsernameTakenException;

class AdminUserServiceTest {

  AdminUserRepository repo;
  PasswordEncoder encoder;
  PlatformTransactionManager txManager;
  DeckTokenRepository deckTokenRepo;
  AdminUserService service;

  @BeforeEach
  void setUp() {
    repo = mock(AdminUserRepository.class);
    encoder = mock(PasswordEncoder.class);
    txManager = mock(PlatformTransactionManager.class);
    deckTokenRepo = mock(DeckTokenRepository.class);
    // Pass-through transaction: TransactionTemplate.execute calls getTransaction first;
    // returning a plain status lets the lambda run as if inside a real transaction. The mock
    // ignores commit/rollback so the test never fails on missing JDBC resources.
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(encoder.encode("password-twelve")).thenReturn("$argon2id$encoded");
    service = new AdminUserService(repo, encoder, txManager, deckTokenRepo);
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

  @Test
  void createInitialAdminTranslatesSerializationFailureToSetupLocked() {
    when(repo.count()).thenReturn(0L);
    // Postgres reports SERIALIZABLE conflicts at COMMIT, not during the insert; the service's
    // try/catch wraps the whole TransactionTemplate so the translation still fires.
    doThrow(new PessimisticLockingFailureException("could not serialize access"))
        .when(txManager)
        .commit(any(TransactionStatus.class));

    assertThatThrownBy(
            () -> service.createInitialAdmin(new CreateAdminCommand("alice", "password-twelve")))
        .isInstanceOf(SetupLockedException.class);
  }

  @Test
  void createInitialAdminTranslatesPkCollisionToSetupLocked() {
    when(repo.count()).thenReturn(0L);
    // Two setups racing with the SAME username — the loser's insert hits the PK before commit.
    doThrow(new DataIntegrityViolationException("admin_user_pkey"))
        .when(repo)
        .insert(anyString(), anyString());

    assertThatThrownBy(
            () -> service.createInitialAdmin(new CreateAdminCommand("alice", "password-twelve")))
        .isInstanceOf(SetupLockedException.class);
  }

  @Test
  void createAdminTranslatesPkCollisionToUsernameTaken() {
    // The check passes (existsByUsername returns false), but a concurrent insert races us to
    // the PK. The service must translate the late-detected violation into the same 409
    // UsernameTakenException the pre-check would have produced.
    when(repo.existsByUsername("bob")).thenReturn(false);
    doThrow(new DataIntegrityViolationException("admin_user_pkey"))
        .when(repo)
        .insert(anyString(), anyString());

    assertThatThrownBy(() -> service.createAdmin(new CreateAdminCommand("bob", "password-twelve")))
        .isInstanceOf(UsernameTakenException.class);
  }
}
