package site.asm0dey.slidev.polls.core.service;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Test-only CDI {@link Event} stand-in that records every payload passed to {@link #fire(Object)}.
 * Replaces the Spring {@code ApplicationEventPublisher} fake the pre-Quarkus tests used: the
 * services now publish via {@code Event<Object>.fire(...)}, so the pure-Java unit tests inject this
 * recorder instead of a real CDI {@code Event} (no Quarkus runtime needed). Only {@code fire} is
 * exercised; the {@code select}/{@code fireAsync} surface throws so an accidental use is loud.
 */
public final class RecordingEvent implements Event<Object> {

  private final List<Object> received = new ArrayList<>();

  @Override
  public void fire(Object event) {
    received.add(event);
  }

  public List<Object> published() {
    return List.copyOf(received);
  }

  @Override
  public <U> CompletionStage<U> fireAsync(U event) {
    throw new UnsupportedOperationException("fireAsync not used in unit tests");
  }

  @Override
  public <U> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
    throw new UnsupportedOperationException("fireAsync not used in unit tests");
  }

  @Override
  public Event<Object> select(Annotation... qualifiers) {
    throw new UnsupportedOperationException("select not used in unit tests");
  }

  @Override
  public <U> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException("select not used in unit tests");
  }

  @Override
  public <U> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException("select not used in unit tests");
  }
}
