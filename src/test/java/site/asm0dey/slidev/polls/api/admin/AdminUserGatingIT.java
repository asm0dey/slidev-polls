package site.asm0dey.slidev.polls.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminUserGatingIT {

  @Autowired MockMvc mvc;
  @Autowired DSLContext dsl;
  @Autowired PasswordEncoder encoder;
  @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(POLLS).execute();
    dsl.deleteFrom(ADMIN_USER).execute();
    AdminUserTestFixtures.seedAdmin(
        dsl, encoder, "alice", "correct-horse-battery"); // bootstrap admin
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "bob", "bobs-old-password-12"); // non-admin
  }

  /**
   * Login using a pre-seeded UUID session so the JDBC SESSION_ID is always 36 characters and
   * expireAll's String comparison against stored session IDs never hits a trailing-space mismatch
   * (CHAR(36) pads short MockHttpSession integer IDs with spaces).
   */
  private MockHttpSession loginWithUuidSession(String user, String pw) throws Exception {
    MockHttpSession session = new MockHttpSession(null, UUID.randomUUID().toString());
    mvc.perform(
            post("/api/admin/login")
                .contentType("application/json")
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}")
                .with(csrf())
                .session(session))
        .andExpect(status().isNoContent());
    return session;
  }

  /**
   * Plain login for tests that only need a valid authenticated session and don't inspect the store.
   */
  private MockHttpSession login(String user, String pw) throws Exception {
    MockHttpSession session = new MockHttpSession();
    mvc.perform(
            post("/api/admin/login")
                .contentType("application/json")
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}")
                .with(csrf())
                .session(session))
        .andExpect(status().isNoContent());
    return session;
  }

  @Test
  void adminResetsAnotherUser_targetCanLoginWithNewPassword_andSessionsExpired() throws Exception {
    loginWithUuidSession("bob", "bobs-old-password-12"); // bob has a live session
    assertThat(sessions.findByPrincipalName("bob")).isNotEmpty();

    MockHttpSession admin = login("alice", "correct-horse-battery");
    mvc.perform(
            post("/api/admin/users/bob/password-reset")
                .session(admin)
                .with(csrf())
                .contentType("application/json")
                .content("{\"newPassword\":\"reset-by-admin-99\"}"))
        .andExpect(status().isNoContent());

    assertThat(sessions.findByPrincipalName("bob")).isEmpty(); // all bob sessions killed
    login("bob", "reset-by-admin-99"); // new password works
  }

  @Test
  void nonAdminReset_is403() throws Exception {
    MockHttpSession bob = login("bob", "bobs-old-password-12");
    mvc.perform(
            post("/api/admin/users/alice/password-reset")
                .session(bob)
                .with(csrf())
                .contentType("application/json")
                .content("{\"newPassword\":\"hijack-attempt-99\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
  }

  @Test
  void adminReset_unknownUser_is404() throws Exception {
    MockHttpSession admin = login("alice", "correct-horse-battery");
    mvc.perform(
            post("/api/admin/users/ghost/password-reset")
                .session(admin)
                .with(csrf())
                .contentType("application/json")
                .content("{\"newPassword\":\"whatever-99-abc\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void nonAdminCreateUser_is403() throws Exception {
    MockHttpSession bob = login("bob", "bobs-old-password-12");
    mvc.perform(
            post("/api/admin/users")
                .session(bob)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"carol\",\"password\":\"carol-password-12\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
  }

  @Test
  void adminCreateUser_is201() throws Exception {
    MockHttpSession admin = login("alice", "correct-horse-battery");
    mvc.perform(
            post("/api/admin/users")
                .session(admin)
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"carol\",\"password\":\"carol-password-12\"}"))
        .andExpect(status().isCreated());
  }
}
