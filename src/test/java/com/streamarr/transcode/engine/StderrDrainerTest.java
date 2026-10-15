package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("UnitTest")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
@DisplayName("Standard Error Drainer Tests")
class StderrDrainerTest {

  // Longer than the class timeout, so a drain thread that never ends fails the test.
  private static final Duration UNTIL_END_OF_STREAM = Duration.ofMinutes(2);

  @Test
  @DisplayName("Should return every line written when the stream closes")
  void shouldReturnEveryLineWrittenWhenTheStreamCloses() throws Exception {
    var output = new PipedOutputStream();
    var input = new PipedInputStream(output);

    try (var drainer = new StderrDrainer(input)) {
      output.write("line1\nline2\nline3\n".getBytes(StandardCharsets.UTF_8));
      output.flush();
      output.close();

      assertThat(drainer.awaitRecentOutput(UNTIL_END_OF_STREAM))
          .containsExactly("line1", "line2", "line3");
    }
  }

  @Test
  @DisplayName("Should return empty list when stream is empty")
  void shouldReturnEmptyListWhenStreamIsEmpty() {
    var input = new ByteArrayInputStream(new byte[0]);

    try (var drainer = new StderrDrainer(input)) {
      assertThat(drainer.awaitRecentOutput(UNTIL_END_OF_STREAM)).isEmpty();
    }
  }

  @Test
  @DisplayName("Should retain only the most recent lines when output exceeds the buffer capacity")
  void shouldRetainOnlyTheMostRecentLinesWhenOutputExceedsTheBufferCapacity() {
    var lines =
        IntStream.rangeClosed(0, StderrDrainer.CAPACITY).mapToObj(index -> "line" + index).toList();
    var input =
        new ByteArrayInputStream(
            (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));

    try (var drainer = new StderrDrainer(input)) {
      // No retry loop: awaiting is the contract — the read must not race the drain thread.
      assertThat(drainer.awaitRecentOutput(UNTIL_END_OF_STREAM))
          .containsExactlyElementsOf(lines.subList(1, lines.size()));
    }
  }

  @Test
  @DisplayName("Should allow idempotent close")
  void shouldAllowIdempotentClose() {
    var input = new ByteArrayInputStream("line\n".getBytes(StandardCharsets.UTF_8));
    var drainer = new StderrDrainer(input);

    assertThatNoException()
        .isThrownBy(
            () -> {
              drainer.close();
              drainer.close();
              drainer.close();
            });
  }
}
