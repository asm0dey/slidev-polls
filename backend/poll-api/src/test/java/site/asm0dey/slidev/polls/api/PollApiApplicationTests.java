package site.asm0dey.slidev.polls.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PollApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
