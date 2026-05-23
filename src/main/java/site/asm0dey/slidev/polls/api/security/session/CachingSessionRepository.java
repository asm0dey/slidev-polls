package site.asm0dey.slidev.polls.api.security.session;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.Map;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Read-through cache over a {@link FindByIndexNameSessionRepository}. Only {@link #findById} is
 * cached (the per-request hot path); {@link #save} refreshes the entry, {@link #deleteById} evicts,
 * and principal lookups pass straight through so session revocation always observes the
 * authoritative store. The short cache TTL (configured on the {@link Cache} in {@link
 * SessionConfig}) bounds cross-node staleness.
 *
 * <p>Cached entries are the delegate's own (mutable) session objects, shared by reference. Within a
 * node, concurrent {@link #findById} callers therefore observe the same instance; correctness
 * relies on the short TTL and the one-principal-per-session access pattern. The revocation path
 * bypasses the cache, so a delete is always authoritative.
 *
 * @param <S> concrete session type of the delegate
 */
public class CachingSessionRepository<S extends Session>
    implements FindByIndexNameSessionRepository<S> {

  private final FindByIndexNameSessionRepository<S> delegate;
  private final Cache<String, S> cache;

  public CachingSessionRepository(
      FindByIndexNameSessionRepository<S> delegate, Cache<String, S> cache) {
    this.delegate = delegate;
    this.cache = cache;
  }

  @Override
  public S createSession() {
    return delegate.createSession();
  }

  @Override
  public void save(S session) {
    delegate.save(session);
    cache.put(session.getId(), session);
  }

  @Override
  public S findById(String id) {
    if (isMalformedId(id)) {
      // A NUL byte never appears in a session id we issued; it arrives only from a foreign or
      // stale SP_SESSION cookie that Spring Session's serializer base64-decoded into garbage.
      // Passing it into the Postgres text lookup throws "invalid byte sequence for encoding UTF8:
      // 0x00" and 500s the request, so treat it as no session — a fresh one is then issued.
      return null;
    }
    S cached = cache.getIfPresent(id);
    if (cached != null) {
      return cached;
    }
    S loaded = delegate.findById(id);
    if (loaded != null) {
      cache.put(id, loaded);
    }
    return loaded;
  }

  @Override
  public void deleteById(String id) {
    if (isMalformedId(id)) {
      return;
    }
    delegate.deleteById(id);
    cache.invalidate(id);
  }

  /**
   * A session id carrying a NUL is not one we minted — it is a base64-decoded foreign/stale cookie.
   * Such an id cannot exist in the store and would crash the Postgres text lookup, so callers treat
   * it as "no session".
   */
  private static boolean isMalformedId(String id) {
    return id == null || id.indexOf('\u0000') >= 0;
  }

  @Override
  public Map<String, S> findByIndexNameAndIndexValue(String indexName, String indexValue) {
    // Uncached: used by revocation; must read the authoritative store.
    return delegate.findByIndexNameAndIndexValue(indexName, indexValue);
  }
}
