package site.asm0dey.slidev.polls.api;

import org.springframework.boot.SpringApplication;

public class TestPollApiApplication {

  static void main(String[] args) {
    SpringApplication.from(PollApiApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
