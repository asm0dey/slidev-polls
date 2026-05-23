package site.asm0dey.slidev.polls.api.security.session;

import java.util.Map;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Expires a principal's persisted sessions. Backed by {@link FindByIndexNameSessionRepository}, so
 * it works across the JDBC store regardless of which node created the session. Deletion is
 * authoritative (rows removed); the per-node Caffeine cache converges within its TTL.
 */
@Component
public class SessionRevoker {

  private final FindByIndexNameSessionRepository<? extends Session> sessions;

  public SessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) {
    this.sessions = sessions;
  }

  /** Deletes every session belonging to {@code username}. */
  public void expireAll(String username) {
    for (String id : idsFor(username)) {
      sessions.deleteById(id);
    }
  }

  /** Deletes every session belonging to {@code username} except the one with {@code keepId}. */
  public void expireAllExcept(String username, String keepId) {
    for (String id : idsFor(username)) {
      if (!id.equals(keepId)) {
        sessions.deleteById(id);
      }
    }
  }

  private Iterable<String> idsFor(String username) {
    Map<String, ? extends Session> found = sessions.findByPrincipalName(username);
    return found.keySet();
  }
}
