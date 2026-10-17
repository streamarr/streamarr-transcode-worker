package com.streamarr.transcode.fakes;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import lombok.NonNull;

/**
 * A process that prints line after line and exits only when it is destroyed, as ffprobe does when a
 * read interval never ends on a stream whose timestamps do not advance.
 */
public final class EndlessOutputProcess extends Process {

  private static final int DESTROYED_EXIT_CODE = 137;

  private final IntFunction<String> lines;
  private final CountDownLatch destroyed = new CountDownLatch(1);

  /** Prints {@code lines.apply(0)}, then {@code lines.apply(1)}, and so on. */
  public EndlessOutputProcess(@NonNull IntFunction<String> lines) {
    this.lines = lines;
  }

  @Override
  public OutputStream getOutputStream() {
    return OutputStream.nullOutputStream();
  }

  @Override
  public InputStream getInputStream() {
    return new InputStream() {
      private int number;
      private byte[] line = new byte[0];
      private int position;

      @Override
      public int read() {
        if (destroyed.getCount() == 0) {
          return -1;
        }

        if (position == line.length) {
          line = (lines.apply(number++) + "\n").getBytes(StandardCharsets.UTF_8);
          position = 0;
        }

        return Byte.toUnsignedInt(line[position++]);
      }
    };
  }

  @Override
  public InputStream getErrorStream() {
    return InputStream.nullInputStream();
  }

  @Override
  public int waitFor() throws InterruptedException {
    destroyed.await();
    return DESTROYED_EXIT_CODE;
  }

  @Override
  public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    return destroyed.await(timeout, unit);
  }

  @Override
  public int exitValue() {
    if (destroyed.getCount() > 0) {
      throw new IllegalThreadStateException("the process is still running");
    }

    return DESTROYED_EXIT_CODE;
  }

  @Override
  public void destroy() {
    destroyed.countDown();
  }

  @Override
  public Process destroyForcibly() {
    destroyed.countDown();
    return this;
  }

  @Override
  public boolean isAlive() {
    return destroyed.getCount() > 0;
  }
}
