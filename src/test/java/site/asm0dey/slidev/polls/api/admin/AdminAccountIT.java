package site.asm0dey.slidev.polls.api.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;
import site.asm0dey.slidev.polls.api.testsupport.AdminUserTestFixtures;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminAccountIT {

  @Autowired DSLContext dsl;
  @Autowired PasswordEncoder encoder;
  @Autowired WebApplicationContext wac;
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(ADMIN_USER).execute();
    mvc =
        MockMvcBuilders.webAppContextSetup(wac)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    // alice created first => bootstrap admin (earliest created_at; ties broken by username asc,
    // and "alice" < "bob" so alice wins even on equal timestamps). bob second => not admin.
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "alice", "correct-horse-battery");
    AdminUserTestFixtures.seedAdmin(dsl, encoder, "bob", "correct-horse-battery");
  }

  private MockHttpSession loginAs(String user) throws Exception {
    var res =
        mvc.perform(
                post("/api/admin/login")
                    .contentType("application/json")
                    .content(
                        "{\"username\":\"" + user + "\",\"password\":\"correct-horse-battery\"}")
                    .with(csrf()))
            .andExpect(status().isNoContent())
            .andReturn();
    return (MockHttpSession) res.getRequest().getSession(false);
  }

  @Test
  void account_returnsUsernameAndIsAdminTrueForBootstrap() throws Exception {
    MockHttpSession s = loginAs("alice");
    mvc.perform(get("/api/admin/account").session(s))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("alice"))
        .andExpect(jsonPath("$.isAdmin").value(true));
  }

  @Test
  void account_isAdminFalseForNonBootstrap() throws Exception {
    MockHttpSession s = loginAs("bob");
    mvc.perform(get("/api/admin/account").session(s))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("bob"))
        .andExpect(jsonPath("$.isAdmin").value(false));
  }

  @Test
  void account_requiresAuth() throws Exception {
    mvc.perform(get("/api/admin/account")).andExpect(status().isUnauthorized());
  }
}
