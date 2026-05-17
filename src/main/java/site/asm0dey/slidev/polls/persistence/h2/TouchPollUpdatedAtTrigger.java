package site.asm0dey.slidev.polls.persistence.h2;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.POLLS;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.tools.TriggerAdapter;
import org.jooq.impl.DSL;

/**
 * Keeps {@code polls.updated_at} fresh when any {@code poll_questions} row on the poll changes. H2
 * triggers must be implemented in Java (no SQL trigger body); we render the actual UPDATE through
 * jOOQ against the generated {@link site.asm0dey.slidev.polls.persistence.jooq.Tables#POLLS} table
 * so the trigger stays in sync with schema renames the same way every other writer does. The
 * matching Postgres trigger lives in V10.
 */
public final class TouchPollUpdatedAtTrigger extends TriggerAdapter {

  @Override
  public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
    ResultSet src = newRow != null ? newRow : oldRow;
    UUID pollId = src.getObject("poll_id", UUID.class);
    if (pollId == null) return;
    DSL.using(conn)
        .update(POLLS)
        .set(POLLS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .where(POLLS.ID.eq(pollId))
        .execute();
  }
}
