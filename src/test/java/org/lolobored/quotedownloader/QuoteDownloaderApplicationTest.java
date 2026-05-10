package org.lolobored.quotedownloader;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(args = {"--json=src/test/resources/test-quotes.json", "--output=/tmp/test-output"})
class QuoteDownloaderApplicationTest {

  @Test
  void contextLoads() {}
}
