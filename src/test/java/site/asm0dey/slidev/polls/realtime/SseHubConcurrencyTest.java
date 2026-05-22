package site.asm0dey.slidev.polls.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Concurrency contract for the ported {@link SseHub}. The real RESTEasy {@code SseBroadcaster}
 * accepts only its own internal {@code SseEventSink} type, so this is a plain unit test that drives
 * {@code SseHub} with a fake {@link Sse} / {@link SseBroadcaster} pair and lightweight fake {@link
 * SseEventSink}s. It pins what {@code SseHub} itself owns: the concurrency-safe parallel tracking
 * map behind {@link SseHub#subscriberCount}/{@link SseHub#pollCount}, the {@code onClose}/{@code
 * onError} pruning, and that {@link SseHub#broadcast} fans to the registered sinks and never throws
 * to the caller — Principle IV (Live-Reliability Over Feature Depth).
 */
class SseHubConcurrencyTest {

  private static SseHub newHub() {
    SseHub hub = new SseHub();
    try {
      Field sseField = SseHub.class.getDeclaredField("sse");
      sseField.setAccessible(true);
      sseField.set(hub, new FakeSse());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return hub;
  }

  @Test
  void concurrent_register_and_unregister_are_consistent() throws Exception {
    SseHub hub = newHub();
    UUID pollId = UUID.randomUUID();
    int workers = 32;
    int churns = 200;
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch gate = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(workers);
    try {
      for (int i = 0; i < workers; i++) {
        pool.submit(
            () -> {
              try {
                gate.await();
                for (int k = 0; k < churns; k++) {
                  SseEventSink e = new FakeSink();
                  hub.register(pollId, e);
                  hub.unregister(pollId, e);
                }
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      gate.countDown();
      assertThat(done.await(15, TimeUnit.SECONDS)).as("workers finished").isTrue();
      assertThat(hub.subscriberCount(pollId)).as("no leaked subscribers").isZero();
      assertThat(hub.pollCount()).as("empty pollId keys pruned").isZero();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void broadcast_reaches_every_registered_subscriber() {
    SseHub hub = newHub();
    UUID pollId = UUID.randomUUID();
    int n = 8;
    List<FakeSink> sinks = new CopyOnWriteArrayList<>();
    for (int i = 0; i < n; i++) {
      FakeSink s = new FakeSink();
      sinks.add(s);
      hub.register(pollId, s);
    }
    assertThat(hub.subscriberCount(pollId)).isEqualTo(n);

    hub.broadcast(pollId, "snapshot", "payload");

    assertThat(sinks.stream().mapToInt(s -> s.sent.get()).sum())
        .as("each subscriber observes one event")
        .isEqualTo(n);
  }

  @Test
  void broadcast_isolates_a_failing_emitter_from_siblings() {
    SseHub hub = newHub();
    UUID pollId = UUID.randomUUID();
    FakeSink a = new FakeSink();
    FakeSink bad = new FakeSink(true);
    FakeSink c = new FakeSink();
    hub.register(pollId, a);
    hub.register(pollId, bad);
    hub.register(pollId, c);

    hub.broadcast(pollId, "tally", "v1");

    // The two healthy sinks received the event; the broadcaster's onError pruned the bad one.
    assertThat(a.sent.get()).isEqualTo(1);
    assertThat(c.sent.get()).isEqualTo(1);
    assertThat(hub.subscriberCount(pollId))
        .as("bad emitter pruned, healthy ones still connected")
        .isEqualTo(2);
  }

  @Test
  void racing_broadcast_and_register_never_throws_to_the_caller() throws Exception {
    SseHub hub = newHub();
    UUID pollId = UUID.randomUUID();
    for (int i = 0; i < 4; i++) {
      hub.register(pollId, new FakeSink());
    }

    int threads = 16;
    int iterations = 500;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch gate = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger errors = new AtomicInteger();
    try {
      for (int i = 0; i < threads; i++) {
        boolean broadcasts = i % 2 == 0;
        pool.submit(
            () -> {
              try {
                gate.await();
                for (int k = 0; k < iterations; k++) {
                  try {
                    if (broadcasts) {
                      hub.broadcast(pollId, "tally", "payload");
                    } else {
                      SseEventSink e = new FakeSink();
                      hub.register(pollId, e);
                      hub.unregister(pollId, e);
                    }
                  } catch (RuntimeException ex) {
                    errors.incrementAndGet();
                  }
                }
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      gate.countDown();
      assertThat(done.await(30, TimeUnit.SECONDS)).as("workers finished").isTrue();
      assertThat(errors.get()).as("no exceptions surfaced to callers").isZero();
    } finally {
      pool.shutdownNow();
    }
  }

  // ---------- fakes ---------------------------------------------------------

  /** Minimal fake sink; counts successful sends, optionally fails to model a dead browser. */
  private static final class FakeSink implements SseEventSink {
    final AtomicInteger sent = new AtomicInteger();
    private final boolean fail;
    private volatile boolean closed;

    FakeSink() {
      this(false);
    }

    FakeSink(boolean fail) {
      this.fail = fail;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public CompletionStage<?> send(OutboundSseEvent event) {
      if (fail) {
        return CompletableFuture.failedFuture(new RuntimeException("broken pipe"));
      }
      sent.incrementAndGet();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /** Fake {@link Sse} producing {@link FakeBroadcaster}s and trivial event builders. */
  private static final class FakeSse implements Sse {
    @Override
    public SseBroadcaster newBroadcaster() {
      return new FakeBroadcaster();
    }

    @Override
    public OutboundSseEvent.Builder newEventBuilder() {
      return new FakeEventBuilder();
    }
  }

  /**
   * Fake broadcaster mirroring the RESTEasy contract used by {@link SseHub}: per-sink failure
   * isolation, and {@code onError} invoked when a sink's send fails (so SseHub prunes it).
   */
  private static final class FakeBroadcaster implements SseBroadcaster {
    private final Set<SseEventSink> sinks = ConcurrentHashMap.newKeySet();
    private final List<BiConsumer<SseEventSink, Throwable>> onError = new CopyOnWriteArrayList<>();
    private final List<Consumer<SseEventSink>> onClose = new CopyOnWriteArrayList<>();

    @Override
    public void onError(BiConsumer<SseEventSink, Throwable> onError) {
      this.onError.add(onError);
    }

    @Override
    public void onClose(Consumer<SseEventSink> onClose) {
      this.onClose.add(onClose);
    }

    @Override
    public void register(SseEventSink sseEventSink) {
      sinks.add(sseEventSink);
    }

    @Override
    public CompletionStage<?> broadcast(OutboundSseEvent event) {
      for (SseEventSink sink : sinks) {
        try {
          sink.send(event)
              .whenComplete(
                  (r, ex) -> {
                    if (ex != null) {
                      sinks.remove(sink);
                      onError.forEach(h -> h.accept(sink, ex));
                    }
                  });
        } catch (RuntimeException ex) {
          sinks.remove(sink);
          onError.forEach(h -> h.accept(sink, ex));
        }
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      sinks.forEach(s -> onClose.forEach(h -> h.accept(s)));
      sinks.clear();
    }

    @Override
    public void close(boolean cascading) {
      close();
    }
  }

  /** Trivial event builder whose {@code build()} returns a no-op {@link OutboundSseEvent}. */
  private static final class FakeEventBuilder implements OutboundSseEvent.Builder {
    @Override
    public OutboundSseEvent.Builder id(String id) {
      return this;
    }

    @Override
    public OutboundSseEvent.Builder name(String name) {
      return this;
    }

    @Override
    public OutboundSseEvent.Builder reconnectDelay(long milliseconds) {
      return this;
    }

    @Override
    public OutboundSseEvent.Builder mediaType(MediaType mediaType) {
      return this;
    }

    @Override
    public OutboundSseEvent.Builder comment(String comment) {
      return this;
    }

    @Override
    public OutboundSseEvent.Builder data(Class type, Object data) {
      return this;
    }

    @Override
    public OutboundSseEvent.Builder data(jakarta.ws.rs.core.GenericType type, Object data) {
      return this;
    }

    @Override
    public OutboundSseEvent.Builder data(Object data) {
      return this;
    }

    @Override
    public OutboundSseEvent build() {
      return null;
    }
  }
}
