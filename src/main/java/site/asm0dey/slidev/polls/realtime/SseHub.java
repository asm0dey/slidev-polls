package site.asm0dey.slidev.polls.realtime;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Fan-out point for SSE subscribers on a poll. Keyed by {@code pollId} (not slug) so a slug
 * rotation mid-session does not drop subscribers — see {@code contracts/sse-events.md §Transport}.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@link #register} is thread-safe and adds emitters to the per-poll set; the returned
 *       emitter is the same instance passed in so callers can chain lifecycle hooks.
 *   <li>{@link #broadcast} iterates the current snapshot of subscribers; per-emitter {@link
 *       IOException} is isolated — one broken browser cannot starve the rest (Principle IV).
 *   <li>{@link #unregister} is safe to call from the emitter's {@code onCompletion} / {@code
 *       onTimeout} / {@code onError} callbacks; it also prunes the pollId key when empty.
 * </ul>
 */
@Component
public class SseHub {

  private static final Logger LOG = System.getLogger(SseHub.class.getName());

  private final ConcurrentMap<UUID, Set<SseEmitter>> byPoll = new ConcurrentHashMap<>();

  /** Register an emitter for {@code pollId}. Returns the emitter unchanged so callers can chain. */
  public SseEmitter register(UUID pollId, SseEmitter emitter) {
    byPoll.computeIfAbsent(pollId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
    emitter.onCompletion(() -> unregister(pollId, emitter));
    emitter.onTimeout(() -> unregister(pollId, emitter));
    emitter.onError(ex -> unregister(pollId, emitter));
    return emitter;
  }

  /** Drop an emitter. Idempotent. Prunes the pollId key once its set is empty. */
  public void unregister(UUID pollId, SseEmitter emitter) {
    byPoll.computeIfPresent(
        pollId,
        (k, set) -> {
          set.remove(emitter);
          return set.isEmpty() ? null : set;
        });
  }

  /**
   * Fan out one event to every current subscriber on {@code pollId}. Failures on one emitter are
   * logged and isolated; the bad emitter is removed from the hub and the broadcast continues.
   */
  public void broadcast(UUID pollId, String eventName, Object payload) {
    Set<SseEmitter> subs = byPoll.get(pollId);
    if (subs == null || subs.isEmpty()) {
      return;
    }
    SseEventBuilder event = SseEmitter.event().name(eventName).data(payload);
    for (SseEmitter emitter : subs) {
      try {
        emitter.send(event);
      } catch (IOException | IllegalStateException ex) {
        // Per-emitter failure isolation — see class javadoc. A dead browser
        // must not starve the other subscribers on the same pollId.
        LOG.log(
            Level.DEBUG, "dropping sse emitter on pollId={0} after send failure: {1}", pollId, ex);
        try {
          emitter.completeWithError(ex);
        } catch (Exception ignored) {
          // emitter may already be complete; fall through to explicit unregister
        }
        unregister(pollId, emitter);
      }
    }
  }

  /** Visible for tests: number of live subscribers on {@code pollId}. */
  public int subscriberCount(UUID pollId) {
    Set<SseEmitter> subs = byPoll.get(pollId);
    return subs == null ? 0 : subs.size();
  }

  /** Visible for tests: number of distinct pollIds currently tracked. */
  public int pollCount() {
    return byPoll.size();
  }

  /**
   * Periodic heartbeat — writes a {@code : keep-alive} SSE comment to every live emitter so
   * intermediate proxies do not close the connection after their idle window (see {@code
   * contracts/sse-events.md §Transport}: "MUST send a comment heartbeat at least every 20
   * seconds"). Runs every 15 seconds — well under the 20s floor — and reuses the broadcast's
   * per-emitter failure-isolation path so a dead browser is pruned on the next tick.
   */
  @Scheduled(fixedDelay = 15_000L)
  public void heartbeat() {
    for (UUID pollId : byPoll.keySet()) {
      broadcastComment(pollId, "keep-alive");
    }
  }

  private void broadcastComment(UUID pollId, String comment) {
    Set<SseEmitter> subs = byPoll.get(pollId);
    if (subs == null || subs.isEmpty()) {
      return;
    }
    SseEventBuilder event = SseEmitter.event().comment(comment);
    for (SseEmitter emitter : subs) {
      try {
        emitter.send(event);
      } catch (IOException | IllegalStateException ex) {
        LOG.log(
            Level.DEBUG,
            "dropping sse emitter on pollId={0} after heartbeat failure: {1}",
            pollId,
            ex);
        try {
          emitter.completeWithError(ex);
        } catch (Exception ignored) {
          // emitter may already be complete; proceed to unregister
        }
        unregister(pollId, emitter);
      }
    }
  }
}
