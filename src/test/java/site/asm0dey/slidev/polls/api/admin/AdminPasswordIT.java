package site.asm0dey.slidev.polls.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

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
class AdminPasswordIT {

  @Autowired MockMvc mvc;
  @Autowired DSLContext dsl;
  @Autowired PasswordEncoder encoder;
  @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(ADMIN_USER).execute();
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "alice", "correct-horse-battery"); // bootstrap
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "bob", "bobs-old-password-12");
  }

  /**
   * Login using a pre-seeded UUID session so the JDBC SESSION_ID is always 36 characters and
   * expireAllExcept's String.equals comparison against request.getSession().getId() never hits a
   * trailing-space mismatch (CHAR(36) pads short MockHttpSession integer IDs with spaces, causing
   * the keep-current comparison to fail in the test harness even though it works in production with
   * real UUIDs).
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
   * Login without cookie-based session tracking (for the three behaviour tests that only need a
   * valid authenticated session object and don't verify session-store counts).
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
  void selfChange_succeeds_andNewPasswordWorks() throws Exception {
    MockHttpSession s = login("bob", "bobs-old-password-12");
    mvc.perform(
            post("/api/admin/account/password")
                .session(s)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"currentPassword\":\"bobs-old-password-12\",\"newPassword\":\"bobs-new-password-34\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(
            post("/api/admin/login")
                .contentType("application/json")
                .content("{\"username\":\"bob\",\"password\":\"bobs-old-password-12\"}")
                .with(csrf()))
        .andExpect(status().isUnauthorized());
    login("bob", "bobs-new-password-34");
  }

  @Test
  void selfChange_wrongCurrent_is403() throws Exception {
    MockHttpSession s = login("bob", "bobs-old-password-12");
    mvc.perform(
            post("/api/admin/account/password")
                .session(s)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"currentPassword\":\"WRONG-password-12\",\"newPassword\":\"bobs-new-password-34\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void selfChange_shortNewPassword_is400() throws Exception {
    MockHttpSession s = login("bob", "bobs-old-password-12");
    mvc.perform(
            post("/api/admin/account/password")
                .session(s)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"currentPassword\":\"bobs-old-password-12\",\"newPassword\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void selfChange_expiresOtherSessions_keepsCurrent() throws Exception {
    // UUID-seeded sessions avoid CHAR(36) trailing-space padding that would cause the
    // String.equals comparison inside expireAllExcept to miss the keep-session.
    loginWithUuidSession("bob", "bobs-old-password-12");
    MockHttpSession current = loginWithUuidSession("bob", "bobs-old-password-12");
    assertThat(sessions.findByPrincipalName("bob")).hasSizeGreaterThanOrEqualTo(2);

    mvc.perform(
            post("/api/admin/account/password")
                .session(current)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"currentPassword\":\"bobs-old-password-12\",\"newPassword\":\"bobs-new-password-34\"}"))
        .andExpect(status().isNoContent());

    assertThat(sessions.findByPrincipalName("bob")).hasSize(1);
  }
}
