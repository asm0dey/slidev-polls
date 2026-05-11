package site.asm0dey.slidev.polls.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Concurrency contract for {@link SseHub}. Exercises racing subscribe / unsubscribe / broadcast
 * threads and verifies that a misbehaving emitter is isolated from its siblings — Principle IV
 * (Live-Reliability Over Feature Depth).
 */
class SseHubConcurrencyTest {

  @Test
  void concurrent_register_and_unregister_are_consistent() throws Exception {
    SseHub hub = new SseHub();
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
                  SseEmitter e = new SseEmitter();
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
      // After the churn completes the hub MUST be back to empty for that pollId.
      assertThat(hub.subscriberCount(pollId)).as("no leaked subscribers").isZero();
      assertThat(hub.pollCount()).as("empty pollId keys pruned").isZero();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void broadcast_reaches_every_registered_subscriber() throws Exception {
    SseHub hub = new SseHub();
    UUID pollId = UUID.randomUUID();
    int n = 8;
    AtomicInteger delivered = new AtomicInteger();
    for (int i = 0; i < n; i++) {
      hub.register(pollId, new CountingEmitter(delivered));
    }
    assertThat(hub.subscriberCount(pollId)).isEqualTo(n);

    hub.broadcast(pollId, "snapshot", "payload");

    assertThat(delivered.get()).as("each subscriber observes one event").isEqualTo(n);
  }

  @Test
  void broadcast_isolates_a_failing_emitter_from_siblings() throws Exception {
    SseHub hub = new SseHub();
    UUID pollId = UUID.randomUUID();
    AtomicInteger good = new AtomicInteger();
    CountingEmitter a = new CountingEmitter(good);
    FailingEmitter bad = new FailingEmitter();
    CountingEmitter c = new CountingEmitter(good);
    hub.register(pollId, a);
    hub.register(pollId, bad);
    hub.register(pollId, c);

    hub.broadcast(pollId, "tally", "v1");

    // Two healthy subscribers still saw the event; the bad one was dropped.
    assertThat(good.get()).as("healthy emitters received the event").isEqualTo(2);
    assertThat(hub.subscriberCount(pollId))
        .as("bad emitter pruned, healthy ones still connected")
        .isEqualTo(2);

    // A second broadcast only fans to the surviving subscribers — the dropped one must not
    // receive anything further either.
    hub.broadcast(pollId, "tally", "v2");
    assertThat(good.get()).isEqualTo(4);
  }

  @Test
  void racing_broadcast_and_register_never_throws_to_the_caller() throws Exception {
    SseHub hub = new SseHub();
    UUID pollId = UUID.randomUUID();
    // Seed with some subscribers so broadcast has work to do.
    List<SseEmitter> seeded =
        List.of(new SseEmitter(), new SseEmitter(), new SseEmitter(), new SseEmitter());
    for (SseEmitter e : seeded) {
      hub.register(pollId, e);
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
                      SseEmitter e = new SseEmitter();
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

  // ---------- helpers -----------------------------------------------------

  /** SseEmitter that increments a counter on every successful send. */
  private static final class CountingEmitter extends SseEmitter {
    private final AtomicInteger delivered;

    CountingEmitter(AtomicInteger delivered) {
      this.delivered = delivered;
    }

    @Override
    public void send(SseEventBuilder builder) {
      delivered.incrementAndGet();
    }
  }

  /** SseEmitter that fails every send — models a dead browser connection. */
  private static final class FailingEmitter extends SseEmitter {
    @Override
    public void send(SseEventBuilder builder) throws IOException {
      throw new IOException("broken pipe");
    }
  }
}
