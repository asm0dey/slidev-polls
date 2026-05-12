package site.asm0dey.slidev.polls.api.admin;

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
import org.springframework.test.web.servlet.MockMvc;
import site.asm0dey.slidev.polls.api.TestcontainersConfiguration;

// V6 leaves admin_user empty, so this IT exercises the empty-DB → setup → locked → 409 chain.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminSetupIT {

  @Autowired private MockMvc mvc;
  @Autowired private DSLContext dsl;

  @BeforeEach
  void wipeAdminUser() {
    // This IT specifically exercises the empty-DB → setup → locked → 409 chain. Spring Boot
    // reuses the same application context across test classes, so alice rows seeded by sibling
    // ITs would leak into the "setupRequired=true" precondition. Wipe polls first because of
    // polls.owner_username FK → admin_user.username, then wipe admin_user.
    dsl.deleteFrom(site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS).execute();
    dsl.deleteFrom(site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER).execute();
  }

  @Test
  void emptyDbReportsSetupRequiredTrueThenRunsSetupThenLocks() throws Exception {
    mvc.perform(get("/api/admin/setup/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.setupRequired").value(true));

    String body =
        """
        { "username": "alice", "password": "correct-horse-battery" }
        """;
    mvc.perform(post("/api/admin/setup").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value("alice"));

    mvc.perform(get("/api/admin/setup/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.setupRequired").value(false));

    mvc.perform(post("/api/admin/setup").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETUP_LOCKED"));
  }

  @Test
  void rejectsShortPasswordWithValidationFailed() throws Exception {
    String body =
        """
        { "username": "alice", "password": "short" }
        """;
    mvc.perform(post("/api/admin/setup").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
