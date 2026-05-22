package site.asm0dey.slidev.polls.api.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionRevokerIT {

  @Autowired private MockMvc mvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SessionRevoker revoker;
  @Autowired private FindByIndexNameSessionRepository<? extends Session> sessions;
  @Autowired private WebApplicationContext wac;

  @BeforeEach
  void setUp() {
    AdminUserTestFixtures.ensureAdmin(dsl, encoder, "alice", "correct-horse-battery");
  }

  private MockHttpSession login() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mvc.perform(
            post("/api/admin/login")
                .session(session)
                .contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"correct-horse-battery\"}")
                .with(csrf()))
        .andExpect(status().isNoContent());
    return session;
  }

  @Test
  void expireAll_killsEverySessionForPrincipal() throws Exception {
    login();
    login();
    assertThat(sessions.findByPrincipalName("alice")).hasSizeGreaterThanOrEqualTo(2);

    revoker.expireAll("alice");

    assertThat(sessions.findByPrincipalName("alice")).isEmpty();
  }

  @Test
  void expireAllExcept_keepsTheNamedSession() throws Exception {
    login();
    // Snapshot the session ids present before the second login.
    java.util.Set<String> beforeIds = sessions.findByPrincipalName("alice").keySet();

    login();
    // The id of the session created by the second login is the one that appeared after the
    // snapshot.
    java.util.Set<String> afterIds =
        new java.util.HashSet<>(sessions.findByPrincipalName("alice").keySet());
    afterIds.removeAll(beforeIds);
    assertThat(afterIds).hasSize(1);
    String keepId = afterIds.iterator().next();

    revoker.expireAllExcept("alice", keepId);

    assertThat(sessions.findByPrincipalName("alice")).containsKey(keepId);
    assertThat(sessions.findByPrincipalName("alice")).hasSize(1);
  }
}
