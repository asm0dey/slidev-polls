package site.asm0dey.slidev.polls.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import site.asm0dey.slidev.polls.core.domain.AdminUser;
import site.asm0dey.slidev.polls.core.error.SetupLockedException;
import site.asm0dey.slidev.polls.core.error.UsernameTakenException;

class AdminUserServiceTest {

  AdminUserRepository repo;
  PasswordEncoder encoder;
  AdminUserService service;

  @BeforeEach
  void setUp() {
    repo = mock(AdminUserRepository.class);
    encoder = mock(PasswordEncoder.class);
    when(encoder.encode("password-twelve")).thenReturn("$argon2id$encoded");
    service = new AdminUserService(repo, encoder);
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
        service.createInitialAdmin(new CreateAdminCommand("alice", "password-twelve", "Alice"));

    verify(encoder, times(1)).encode("password-twelve");
    verify(repo).insert("alice", "$argon2id$encoded", "Alice");
    assertThat(created.username()).isEqualTo("alice");
  }

  @Test
  void createInitialAdminThrowsWhenTableAlreadyPopulated() {
    when(repo.count()).thenReturn(1L);

    assertThatThrownBy(
            () ->
                service.createInitialAdmin(
                    new CreateAdminCommand("alice", "password-twelve", "Alice")))
        .isInstanceOf(SetupLockedException.class);

    verify(repo, never()).insert(anyString(), anyString(), anyString());
  }

  @Test
  void createAdminThrowsWhenUsernameTaken() {
    when(repo.existsByUsername("alice")).thenReturn(true);

    assertThatThrownBy(
            () -> service.createAdmin(new CreateAdminCommand("alice", "password-twelve", "Alice")))
        .isInstanceOf(UsernameTakenException.class);
  }

  @Test
  void createAdminInsertsWhenUsernameFree() {
    when(repo.existsByUsername("bob")).thenReturn(false);
    when(encoder.encode("password-twelve")).thenReturn("$argon2id$encoded");

    service.createAdmin(new CreateAdminCommand("bob", "password-twelve", "Bob"));

    verify(repo).insert("bob", "$argon2id$encoded", "Bob");
  }

  @Test
  void listAdminsDelegatesToRepository() {
    var bob = new AdminUser("bob", "Bob", Instant.parse("2026-05-09T00:00:00Z"));
    when(repo.listAll()).thenReturn(List.of(bob));

    assertThat(service.listAdmins()).containsExactly(bob);
  }

  @Test
  void rejectsBlankUsername() {
    assertThatThrownBy(
            () ->
                service.createInitialAdmin(new CreateAdminCommand("", "password-twelve", "Alice")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("username");
  }

  @Test
  void rejectsShortPassword() {
    assertThatThrownBy(
            () -> service.createInitialAdmin(new CreateAdminCommand("alice", "short", "Alice")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("password");
  }

  @Test
  void rejectsUppercaseUsernameToMatchCheckConstraint() {
    assertThatThrownBy(
            () ->
                service.createInitialAdmin(
                    new CreateAdminCommand("Alice", "password-twelve", "Alice")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("username");
  }
}
