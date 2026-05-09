package site.asm0dey.slidev.polls.api.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminUserManagementIT {

  @Autowired private MockMvc mvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  @BeforeEach
  void seedAlice() {
    // Wipe polls first so polls.owner_username FK doesn't block the admin_user wipe — sibling
    // ITs (PollAuthoringIT, etc.) leave alice-owned polls in the shared Spring context.
    dsl.deleteFrom(site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS).execute();
    dsl.deleteFrom(site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER).execute();
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "alice", "correct-horse-battery", "Alice");
  }

  @Test
  void anonymousGetReturns401() throws Exception {
    mvc.perform(get("/api/admin/users"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  @Test
  void authenticatedPostCreatesUserAndGetListsBoth() throws Exception {
    MockHttpSession session = loginAsAlice();
    String body =
        """
        { "username": "bob", "password": "another-strong-pw", "displayName": "Bob" }
        """;
    mvc.perform(
            post("/api/admin/users")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value("bob"));

    mvc.perform(get("/api/admin/users").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[?(@.username == 'alice')]").exists())
        .andExpect(jsonPath("$[?(@.username == 'bob')]").exists());
  }

  @Test
  void duplicateUsernameReturns409UsernameTaken() throws Exception {
    MockHttpSession session = loginAsAlice();
    String body =
        """
        { "username": "alice", "password": "another-strong-pw", "displayName": "Alice 2" }
        """;
    mvc.perform(
            post("/api/admin/users")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
  }

  private MockHttpSession loginAsAlice() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mvc.perform(
            post("/api/admin/login")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"correct-horse-battery\"}"))
        .andExpect(status().isNoContent());
    return session;
  }
}
