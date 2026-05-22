package site.asm0dey.slidev.polls.core.service;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.function.Supplier;

/**
 * Test-only CDI {@link Instance} stand-in returning a single supplied bean from {@link #get()}.
 * Replaces the Spring {@code ObjectProvider} the pre-Quarkus tests used for {@code PollService}
 * self-injection: production code resolves the contextual proxy via {@code Instance.get()}, so the
 * pure-Java unit tests hand the service a lazily-resolved supplier instead of standing up CDI. Only
 * {@code get} is exercised; the selection/iteration surface throws so accidental use is loud.
 */
public final class SelfInstance<T> implements Instance<T> {

  private final Supplier<T> supplier;

  public SelfInstance(Supplier<T> supplier) {
    this.supplier = supplier;
  }

  @Override
  public T get() {
    return supplier.get();
  }

  @Override
  public Instance<T> select(Annotation... qualifiers) {
    throw new UnsupportedOperationException("select not used in unit tests");
  }

  @Override
  public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException("select not used in unit tests");
  }

  @Override
  public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException("select not used in unit tests");
  }

  @Override
  public boolean isUnsatisfied() {
    return false;
  }

  @Override
  public boolean isAmbiguous() {
    return false;
  }

  @Override
  public void destroy(T instance) {
    // no-op for unit tests
  }

  @Override
  public Iterator<T> iterator() {
    throw new UnsupportedOperationException("iterator not used in unit tests");
  }

  @Override
  public Handle<T> getHandle() {
    throw new UnsupportedOperationException("getHandle not used in unit tests");
  }

  @Override
  public Iterable<? extends Handle<T>> handles() {
    throw new UnsupportedOperationException("handles not used in unit tests");
  }
}
