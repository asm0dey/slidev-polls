package site.asm0dey.slidev.polls.api.pub;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.time.Instant;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.error.NotFoundException;
import site.asm0dey.slidev.polls.core.service.PollRepository;
import site.asm0dey.slidev.polls.core.slug.SlugValidator;
import site.asm0dey.slidev.polls.realtime.SseHub;
import site.asm0dey.slidev.polls.realtime.sse.SnapshotBuilder;
import site.asm0dey.slidev.polls.realtime.sse.SnapshotPayload;

/**
 * SSE endpoint backing {@code <PollResults/>}. Each subscriber's {@link SseEventSink} is held open
 * by Quarkus REST; the hub keys subscribers by {@code pollId} so a mid-session slug rotation does
 * not drop the stream (see {@code contracts/sse-events.md §Transport}).
 *
 * <p>On connect the resource resolves slug → poll, emits an initial {@code snapshot} event so the
 * client starts from a consistent state (Principle IV, SC-006), then registers the sink with the
 * hub for subsequent {@code tally} / {@code snapshot} / {@code question-closed} fan-out from {@code
 * TallyBroadcaster}. A malformed or unknown slug surfaces as 404 {@code NOT_FOUND} via the shared
 * {@code DomainExceptionMappers} — same edge behaviour as {@link PublicPollController#getBySlug}.
 *
 * <p>Runs on a virtual thread: the blocking poll lookup + initial snapshot build happen off the
 * event loop, and the sink stays open after the method returns.
 */
@Path("/api/polls")
@ApplicationScoped
@RunOnVirtualThread
public class StreamController {

  private final PollRepository pollRepository;
  private final SseHub hub;
  private final SnapshotBuilder snapshots;

  public StreamController(PollRepository pollRepository, SseHub hub, SnapshotBuilder snapshots) {
    this.pollRepository = pollRepository;
    this.hub = hub;
    this.snapshots = snapshots;
  }

  @GET
  @Path("/{slug}/stream")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  public void stream(@PathParam("slug") String slug, @Context SseEventSink sink, @Context Sse sse) {
    if (!SlugValidator.isValidFormat(slug)) {
      throw new NotFoundException("no poll with slug '" + slug + "'");
    }
    Poll poll =
        pollRepository
            .findBySlug(slug)
            .orElseThrow(() -> new NotFoundException("no poll with slug '" + slug + "'"));

    SnapshotPayload initial = snapshots.build(poll, Instant.now());
    sink.send(
        sse.newEventBuilder()
            .name("snapshot")
            .mediaType(MediaType.APPLICATION_JSON_TYPE)
            .data(initial)
            .build());
    hub.register(poll.id(), sink);
  }
}
