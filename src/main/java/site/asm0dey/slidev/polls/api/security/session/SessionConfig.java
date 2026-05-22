package site.asm0dey.slidev.polls.api.security.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * Enables JDBC-backed HTTP sessions and wraps the resulting repository in a Caffeine read cache.
 * Exposes a {@link SessionRegistry} backed by the cache so the app can enumerate and expire a
 * principal's sessions.
 *
 * <p>{@code @EnableJdbcHttpSession} registers a {@code JdbcIndexedSessionRepository} under the bean
 * name {@code "sessionRepository"}. The {@link #cachingSessionRepository} method uses a
 * {@code @Qualifier} to inject that bean directly (avoiding the circular-dependency that would
 * arise if Spring resolved the {@link Primary} {@code CachingSessionRepository} instead).
 */
@Configuration(proxyBeanMethods = false)
@EnableJdbcHttpSession(cleanupCron = "0 * * * * *")
public class SessionConfig {

  /** Short TTL bounds how long a revoked session can survive in a node's cache. */
  @Bean
  Cache<String, Session> sessionCache() {
    return Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(5))
        .maximumSize(10_000)
        .build();
  }

  /**
   * Marked {@link Primary} so the session filter and the registry use the cached repository rather
   * than the raw {@code JdbcIndexedSessionRepository} bean. The {@link Qualifier} pins injection to
   * the JDBC repository by name, side-stepping the circular-dependency that arises when Spring
   * tries to satisfy a {@code FindByIndexNameSessionRepository} parameter and picks this {@link
   * Primary} bean instead.
   */
  @Bean
  @Primary
  @SuppressWarnings("unchecked")
  CachingSessionRepository<Session> cachingSessionRepository(
      @Qualifier("sessionRepository") FindByIndexNameSessionRepository<? extends Session> delegate,
      Cache<String, Session> cache) {
    return new CachingSessionRepository<>(
        (FindByIndexNameSessionRepository<Session>) delegate, cache);
  }

  @Bean
  SessionRegistry sessionRegistry(CachingSessionRepository<Session> repository) {
    return new SpringSessionBackedSessionRegistry<>(repository);
  }
}
