package site.asm0dey.slidev.polls.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entrypoint. The {@code scanBasePackages} value is widened one level (to {@code
 * site.asm0dey.slidev.polls}) so the reactor's sibling modules — {@code poll-core} (services) and
 * {@code poll-persistence} (repositories) — publish their {@code @Service}/{@code @Repository}
 * beans into the same application context as the {@code @RestController}s in this module. Without
 * this, {@code PollController} cannot find {@code PollService}.
 */
@SpringBootApplication(scanBasePackages = "site.asm0dey.slidev.polls")
@EnableScheduling
public class PollApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(PollApiApplication.class, args);
  }
}
