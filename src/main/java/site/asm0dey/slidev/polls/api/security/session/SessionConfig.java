package site.asm0dey.slidev.polls.api.security.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
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
 *
 * <p>The cleanup schedule is the single source of truth in {@code application.yml} under {@code
 * spring.session.jdbc.cleanup-cron}; it is applied via a {@link SessionRepositoryCustomizer} so the
 * {@code @EnableJdbcHttpSession} annotation does not need to duplicate it.
 */
@Configuration(proxyBeanMethods = false)
@EnableJdbcHttpSession
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
   * Applies the cleanup cron from {@code application.yml} so the annotation does not duplicate the
   * schedule. {@code @EnableJdbcHttpSession} does not resolve {@code ${…}} placeholders in its
   * {@code cleanupCron} attribute, so a {@link SessionRepositoryCustomizer} is the correct way to
   * propagate the property.
   */
  @Bean
  SessionRepositoryCustomizer<JdbcIndexedSessionRepository> cleanupCronCustomizer(
      @Value("${spring.session.jdbc.cleanup-cron:0 * * * * *}") String cleanupCron) {
    return repo -> repo.setCleanupCron(cleanupCron);
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
