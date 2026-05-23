package site.asm0dey.slidev.polls.api.security.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;

/**
 * Unit coverage for the read-through caching wrapper, focused on the malformed-session-id guard.
 *
 * <p>Regression: after the v0.5.0 switch to Spring Session JDBC, a returning visitor's stale {@code
 * SP_SESSION} cookie (an old Tomcat in-memory session id) is base64-decoded by Spring Session's
 * cookie serializer into bytes containing a {@code 0x00}. Passing that NUL-bearing id into the
 * Postgres {@code WHERE SESSION_ID = ?} lookup throws {@code invalid byte sequence for encoding
 * "UTF8": 0x00} and 500s every such request. The wrapper must treat a malformed id as "no session"
 * and never touch the store.
 */
class CachingSessionRepositoryTest {

  private static final class RecordingRepository
      implements FindByIndexNameSessionRepository<MapSession> {
    final List<String> findCalls = new ArrayList<>();
    final List<String> deleteCalls = new ArrayList<>();
    final Map<String, MapSession> store = new HashMap<>();

    @Override
    public MapSession createSession() {
      return new MapSession();
    }

    @Override
    public void save(MapSession session) {
      store.put(session.getId(), session);
    }

    @Override
    public MapSession findById(String id) {
      findCalls.add(id);
      return store.get(id);
    }

    @Override
    public void deleteById(String id) {
      deleteCalls.add(id);
      store.remove(id);
    }

    @Override
    public Map<String, MapSession> findByIndexNameAndIndexValue(String name, String value) {
      return Map.of();
    }
  }

  private static CachingSessionRepository<MapSession> repo(RecordingRepository delegate) {
    Cache<String, MapSession> cache = Caffeine.newBuilder().build();
    return new CachingSessionRepository<>(delegate, cache);
  }

  @Test
  void findById_withNulByteInId_returnsNullWithoutQueryingDelegate() {
    RecordingRepository delegate = new RecordingRepository();

    MapSession result = repo(delegate).findById("old-tomcat-cookie\u0000garbage");

    assertThat(result).isNull();
    assertThat(delegate.findCalls)
        .as("delegate must not be queried with a NUL-bearing id")
        .isEmpty();
  }

  @Test
  void deleteById_withNulByteInId_isNoOp() {
    RecordingRepository delegate = new RecordingRepository();

    repo(delegate).deleteById("bad\u0000id");

    assertThat(delegate.deleteCalls)
        .as("delegate must not be deleted with a NUL-bearing id")
        .isEmpty();
  }

  @Test
  void findById_withValidId_delegatesAndCaches() {
    RecordingRepository delegate = new RecordingRepository();
    MapSession session = new MapSession();
    delegate.store.put(session.getId(), session);
    CachingSessionRepository<MapSession> repo = repo(delegate);

    MapSession first = repo.findById(session.getId());
    MapSession second = repo.findById(session.getId());

    assertThat(first).isSameAs(session);
    assertThat(second).isSameAs(session);
    // One delegate hit; the second read is served from the cache.
    assertThat(delegate.findCalls).containsExactly(session.getId());
  }
}
