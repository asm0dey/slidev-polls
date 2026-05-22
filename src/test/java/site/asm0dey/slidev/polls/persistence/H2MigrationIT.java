package site.asm0dey.slidev.polls.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class H2MigrationIT extends AbstractH2Test {

  @Test
  void baseline_creates_expected_tables() throws Exception {
    DataSource ds = freshH2();
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      var rs =
          s.executeQuery(
              "SELECT count(*) FROM information_schema.tables "
                  + "WHERE lower(table_name) IN "
                  + "('admin_user','polls','poll_questions','poll_options','votes',"
                  + "'deck_tokens','poll_allowed_origins')");
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(7);
    }
  }

  @Test
  void one_active_question_invariant_holds_on_h2() throws Exception {
    DataSource ds = freshH2();
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("INSERT INTO admin_user(username, password_hash) VALUES ('h','x')");
      UUID pollId = UUID.randomUUID();
      s.execute(
          "INSERT INTO polls(id, owner_username, title, slug) "
              + "VALUES ('"
              + pollId
              + "','h','title','test-slug')");
      s.execute(
          "INSERT INTO poll_questions(id, poll_id, prompt, ordinal, status, activated_at) "
              + "VALUES ('"
              + UUID.randomUUID()
              + "','"
              + pollId
              + "','p1',0,'ACTIVE',CURRENT_TIMESTAMP)");
      try {
        s.execute(
            "INSERT INTO poll_questions(id, poll_id, prompt, ordinal, status, activated_at) "
                + "VALUES ('"
                + UUID.randomUUID()
                + "','"
                + pollId
                + "','p2',1,'ACTIVE',CURRENT_TIMESTAMP)");
        org.junit.jupiter.api.Assertions.fail("expected unique violation");
      } catch (Exception expected) {
        /* expected */
      }
    }
  }

  @Test
  void slug_case_insensitive_unique_holds_on_h2() throws Exception {
    DataSource ds = freshH2();
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("INSERT INTO admin_user(username, password_hash) VALUES ('h','x')");
      s.execute(
          "INSERT INTO polls(id, owner_username, title, slug) "
              + "VALUES ('"
              + UUID.randomUUID()
              + "','h','title','SameSlug')");
      try {
        s.execute(
            "INSERT INTO polls(id, owner_username, title, slug) "
                + "VALUES ('"
                + UUID.randomUUID()
                + "','h','title','sameslug')");
        org.junit.jupiter.api.Assertions.fail("expected unique violation");
      } catch (Exception expected) {
        /* expected */
      }
    }
  }
}
