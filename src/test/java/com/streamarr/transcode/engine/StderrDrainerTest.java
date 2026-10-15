package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
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

  // The open stream never ends, so any wait expires; a short one keeps the test fast.
  private static final Duration UNTIL_TIMEOUT = Duration.ofMillis(50);

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
  @DisplayName("Should return the lines read so far when the stream stays open past the timeout")
  void shouldReturnTheLinesReadSoFarWhenTheStreamStaysOpenPastTheTimeout() throws Exception {
    var input = new OpenStderr("line1\nline2\n");

    try (var drainer = new StderrDrainer(input)) {
      input.awaitDrainedToEnd();

      assertThat(drainer.awaitRecentOutput(UNTIL_TIMEOUT)).containsExactly("line1", "line2");
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

  /**
   * Serves its text, then blocks like a stderr pipe whose writer never closes. A reader asks for
   * more bytes only after it has returned every line it holds, so that request marks the point
   * where the drainer has buffered all the text.
   */
  private static final class OpenStderr extends InputStream {

    private final ByteArrayInputStream text;
    private final CountDownLatch drainedToEnd = new CountDownLatch(1);
    private final CountDownLatch neverClosed = new CountDownLatch(1);

    OpenStderr(String text) {
      this.text = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    void awaitDrainedToEnd() throws InterruptedException {
      drainedToEnd.await();
    }

    @Override
    public int read() throws IOException {
      var single = new byte[1];
      return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (text.available() > 0) {
        return text.read(bytes, offset, length);
      }

      drainedToEnd.countDown();
      try {
        neverClosed.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("stderr read interrupted");
      }

      return -1;
    }
  }
}
