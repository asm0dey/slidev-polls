package site.asm0dey.slidev.polls.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.h2.api.Trigger;

/**
 * Keeps {@code polls.updated_at} fresh when any {@code poll_questions} row on the poll changes. H2
 * has no SQL-level trigger bodies, so the action is implemented in Java; the matching PG trigger
 * lives in V10.
 */
public final class TouchPollUpdatedAtTrigger implements Trigger {

  private int pollIdColumnIndex = -1;

  @Override
  public void init(
      Connection conn,
      String schemaName,
      String triggerName,
      String tableName,
      boolean before,
      int type)
      throws SQLException {
    try (var rs = conn.getMetaData().getColumns(null, schemaName, tableName, "poll_id")) {
      if (rs.next()) {
        pollIdColumnIndex = rs.getInt("ORDINAL_POSITION") - 1;
      }
    }
    if (pollIdColumnIndex < 0) {
      throw new SQLException("poll_id column not found on " + schemaName + "." + tableName);
    }
  }

  @Override
  public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
    Object pollId = (newRow != null) ? newRow[pollIdColumnIndex] : oldRow[pollIdColumnIndex];
    if (pollId == null) return;
    try (PreparedStatement ps =
        conn.prepareStatement("UPDATE polls SET updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
      ps.setObject(1, pollId);
      ps.executeUpdate();
    }
  }

  @Override
  public void close() {}

  @Override
  public void remove() {}
}
