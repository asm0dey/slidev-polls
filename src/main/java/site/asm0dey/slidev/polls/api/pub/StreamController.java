package site.asm0dey.slidev.polls.api.pub;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.slug.SlugValidator;
import site.asm0dey.slidev.polls.realtime.SseHub;
import site.asm0dey.slidev.polls.realtime.sse.SnapshotBuilder;
import site.asm0dey.slidev.polls.realtime.sse.SnapshotPayload;

/**
 * SSE endpoint backing {@code <PollResults/>}. Long-lived per-subscriber {@link SseEmitter} held
 * open by Spring MVC; the hub keys subscribers by {@code pollId} so a mid-session slug rotation
 * does not drop the stream (see {@code contracts/sse-events.md §Transport}).
 *
 * <p>On connect the controller resolves slug → poll, emits an initial {@code snapshot} event so the
 * client starts from a consistent state (Principle IV, SC-006), then registers the emitter with the
 * hub for subsequent {@code tally} / {@code snapshot} / {@code question-closed} fan-out from {@code
 * TallyBroadcaster}. A malformed or unknown slug surfaces as 404 {@code NOT_FOUND} — same edge
 * behaviour as {@link PublicPollController#getBySlug}.
 */
@RestController
@RequestMapping("/api/polls")
public class StreamController {

  private static final Logger LOG = System.getLogger(StreamController.class.getName());

  /**
   * 24h absolute ceiling on the emitter hold-open. The browser will reconnect well before this
   * fires; the timeout only exists so a client that walks away from their laptop overnight does not
   * wedge a worker thread forever.
   */
  private static final long EMITTER_TIMEOUT_MS = 24L * 60L * 60L * 1000L;

  private final PollRepository pollRepository;
  private final SseHub hub;
  private final SnapshotBuilder snapshots;

  public StreamController(PollRepository pollRepository, SseHub hub, SnapshotBuilder snapshots) {
    this.pollRepository = pollRepository;
    this.hub = hub;
    this.snapshots = snapshots;
  }

  @GetMapping("/{slug}/stream")
  public SseEmitter stream(@PathVariable String slug) {
    if (!SlugValidator.isValidFormat(slug)) {
      throw new NotFoundException("no poll with slug '" + slug + "'");
    }
    Poll poll =
        pollRepository
            .findBySlug(slug)
            .orElseThrow(() -> new NotFoundException("no poll with slug '" + slug + "'"));

    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
    SnapshotPayload initial = snapshots.build(poll, Instant.now());
    try {
      emitter.send(SseEmitter.event().name("snapshot").data(initial));
    } catch (IOException ex) {
      // Connection died before we could write the initial snapshot — client will reconnect and
      // we'll try again. Don't propagate; logging is enough.
      LOG.log(Level.DEBUG, "initial snapshot send failed for slug {0}: {1}", slug, ex);
      emitter.completeWithError(ex);
      return emitter;
    }
    return hub.register(poll.id(), emitter);
  }
}
