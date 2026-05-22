package site.asm0dey.slidev.polls.realtime;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fan-out point for SSE subscribers on a poll. Keyed by {@code pollId} (not slug) so a slug
 * rotation mid-session does not drop subscribers — see {@code contracts/sse-events.md §Transport}.
 *
 * <p>Built on the JAX-RS {@link SseBroadcaster}: one broadcaster per {@code pollId} fans an {@code
 * OutboundSseEvent} to every registered {@link SseEventSink}. Per-sink failure isolation is handled
 * by the broadcaster — a dead browser cannot starve the rest (Principle IV) — and {@code
 * onClose}/{@code onError} prune the sink from the parallel tracking set, dropping the {@code
 * pollId} key once it is empty.
 *
 * <p>{@link SseBroadcaster} exposes no subscriber count, so a parallel {@code ConcurrentMap<UUID,
 * Set<SseEventSink>>} backs the test-visible {@link #subscriberCount} / {@link #pollCount}.
 */
@ApplicationScoped
public class SseHub {

  private static final Logger LOG = System.getLogger(SseHub.class.getName());

  @Inject Sse sse;

  private final ConcurrentMap<UUID, SseBroadcaster> byPoll = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, Set<SseEventSink>> sinks = new ConcurrentHashMap<>();

  /**
   * Register a sink for {@code pollId}. Creates the per-poll broadcaster on the first sink and
   * wires {@code onClose}/{@code onError} so the sink is dropped from the tracking set and the
   * {@code pollId} key is pruned once empty.
   */
  public void register(UUID pollId, SseEventSink sink) {
    SseBroadcaster broadcaster =
        byPoll.computeIfAbsent(
            pollId,
            k -> {
              SseBroadcaster nb = sse.newBroadcaster();
              nb.onClose(s -> drop(pollId, s));
              nb.onError((s, ex) -> drop(pollId, s));
              return nb;
            });
    sinks.computeIfAbsent(pollId, k -> ConcurrentHashMap.newKeySet()).add(sink);
    broadcaster.register(sink);
  }

  /**
   * Remove {@code sink} from the parallel tracking set for {@code pollId}; prune the {@code pollId}
   * key (and its broadcaster) once the set is empty. Idempotent; safe to call from {@code
   * onClose}/{@code onError} callbacks.
   */
  public void unregister(UUID pollId, SseEventSink sink) {
    drop(pollId, sink);
  }

  private void drop(UUID pollId, SseEventSink sink) {
    sinks.computeIfPresent(
        pollId,
        (k, set) -> {
          set.remove(sink);
          return set.isEmpty() ? null : set;
        });
    if (!sinks.containsKey(pollId)) {
      byPoll.remove(pollId);
    }
  }

  /**
   * Fan out one event to every current subscriber on {@code pollId}. Per-sink send failures are
   * isolated by the broadcaster; a dead browser must not starve the other subscribers on the same
   * pollId (Principle IV).
   */
  public void broadcast(UUID pollId, String eventName, Object payload) {
    SseBroadcaster broadcaster = byPoll.get(pollId);
    if (broadcaster == null) {
      return;
    }
    broadcaster.broadcast(
        sse.newEventBuilder()
            .name(eventName)
            .mediaType(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
            .data(payload)
            .build());
  }

  /** Visible for tests: number of live subscribers on {@code pollId}. */
  public int subscriberCount(UUID pollId) {
    Set<SseEventSink> subs = sinks.get(pollId);
    return subs == null ? 0 : subs.size();
  }

  /** Visible for tests: number of distinct pollIds currently tracked. */
  public int pollCount() {
    return sinks.size();
  }

  /**
   * Periodic heartbeat — broadcasts a {@code : keep-alive} SSE comment to every live sink so
   * intermediate proxies do not close the connection after their idle window (see {@code
   * contracts/sse-events.md §Transport}: "MUST send a comment heartbeat at least every 20
   * seconds"). Runs every 15 seconds — well under the 20s floor — and reuses the broadcaster's
   * per-sink failure-isolation path so a dead browser is pruned via {@code onError}.
   */
  @Scheduled(every = "15s")
  public void heartbeat() {
    for (UUID pollId : byPoll.keySet()) {
      SseBroadcaster broadcaster = byPoll.get(pollId);
      if (broadcaster == null) {
        continue;
      }
      try {
        broadcaster.broadcast(sse.newEventBuilder().comment("keep-alive").build());
      } catch (RuntimeException ex) {
        LOG.log(Level.DEBUG, "heartbeat broadcast failed on pollId={0}: {1}", pollId, ex);
      }
    }
  }
}
