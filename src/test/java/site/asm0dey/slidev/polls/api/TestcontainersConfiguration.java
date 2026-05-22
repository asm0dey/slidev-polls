package site.asm0dey.slidev.polls.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.session.SessionIdGenerator;
import org.springframework.session.UuidSessionIdGenerator;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
  }

  /**
   * Makes Spring Session co-operate with MockMvc's {@link
   * org.springframework.mock.web.MockHttpSession}.
   *
   * <p>When {@code SessionRepositoryFilter} creates a new session it normally generates a fresh
   * UUID. In a MockMvc test the caller passes a {@code MockHttpSession} via {@code
   * RequestBuilder.session(…)}; that session object ends up on the underlying {@code
   * MockHttpServletRequest} which is accessible through {@code RequestContextHolder}. By re-using
   * <em>that</em> session's ID (creating one if none exists yet) we ensure the Spring Session and
   * the {@code MockHttpSession} share the same identifier, so the test can carry the mock-session
   * object across multiple {@code perform()} calls and the filter will find the persisted Spring
   * Session on each subsequent request.
   *
   * <p>The generator must unwrap any {@link HttpServletRequestWrapper} chain (the {@code
   * SessionRepositoryFilter} wraps the request before session creation is triggered) to reach the
   * original {@code MockHttpServletRequest} where the native session lives. Calling {@code
   * getSession(true)} on the wrapper would recurse infinitely.
   */
  @Bean
  SessionIdGenerator mockMvcAwareSessionIdGenerator() {
    return () -> {
      RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
      if (attrs instanceof ServletRequestAttributes sra) {
        // Unwrap any HttpServletRequestWrapper chain to reach the raw MockHttpServletRequest.
        HttpServletRequest underlying = sra.getRequest();
        while (underlying instanceof HttpServletRequestWrapper wrapper) {
          underlying = (HttpServletRequest) wrapper.getRequest();
        }
        // Force creation of the native MockHttpSession (no-op if one already exists).
        HttpSession nativeSession = underlying.getSession(true);
        if (nativeSession != null) {
          return nativeSession.getId();
        }
      }
      return UuidSessionIdGenerator.getInstance().generate();
    };
  }

  /**
   * Extends cookie-based session resolution with a fallback to the native (mock) session ID so that
   * follow-up MockMvc requests carrying a {@code MockHttpSession} (but no {@code SESSION} cookie)
   * can still locate the Spring Session that was persisted during the login request.
   */
  @Bean
  HttpSessionIdResolver mockMvcAwareSessionIdResolver() {
    CookieHttpSessionIdResolver cookieResolver = new CookieHttpSessionIdResolver();
    return new HttpSessionIdResolver() {
      @Override
      public List<String> resolveSessionIds(HttpServletRequest request) {
        List<String> ids = cookieResolver.resolveSessionIds(request);
        if (!ids.isEmpty()) {
          return ids;
        }
        // Unwrap to reach the underlying MockHttpServletRequest.
        HttpServletRequest underlying = request;
        while (underlying instanceof HttpServletRequestWrapper wrapper) {
          underlying = (HttpServletRequest) wrapper.getRequest();
        }
        HttpSession nativeSession = underlying.getSession(false);
        if (nativeSession != null) {
          return List.of(nativeSession.getId());
        }
        return ids;
      }

      @Override
      public void setSessionId(
          HttpServletRequest request, HttpServletResponse response, String sessionId) {
        cookieResolver.setSessionId(request, response, sessionId);
      }

      @Override
      public void expireSession(HttpServletRequest request, HttpServletResponse response) {
        cookieResolver.expireSession(request, response);
      }
    };
  }
}
