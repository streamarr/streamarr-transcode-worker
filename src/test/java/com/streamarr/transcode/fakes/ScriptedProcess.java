package com.streamarr.transcode.fakes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Builder;
import lombok.NonNull;

/**
 * A process whose standard output replays scripted bytes, standing in for FFmpeg.
 *
 * <p>The output can pause at one offset until the test or a {@code q} on standard input resumes it,
 * and can fail with an I/O error at another. The process exits at the end of its output, at launch,
 * or when the test says so. A forcible destroy ends the output where it stands and exits with 137.
 */
public final class ScriptedProcess extends Process {

  public static final int FORCIBLY_DESTROYED_EXIT_CODE = 137;

  private static final long PROCESS_ID = 4242;

  /** When the process exits relative to the reader reaching the end of its output. */
  public enum ExitTiming {
    /** Once the reader has read every byte, as FFmpeg closes its output when it exits. */
    AT_END_OF_OUTPUT,
    /** Immediately, with the whole output still unread, as if held in the pipe. */
    AT_LAUNCH,
    /** Only when the test calls {@link #exit()}. */
    WHEN_TEST_EXITS
  }

  private final byte[] output;
  private final int exitCode;
  private final ExitTiming exitTiming;
  private final OptionalInt pauseOffset;
  private final OptionalInt readFailureOffset;
  private final boolean resumesOnQuit;
  private final byte[] stderr;
  private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
  private final CompletableFuture<Integer> exit = new CompletableFuture<>();
  private final InputStream stdout = new ScriptedOutput();

  private int position;
  private int endOffset;
  private boolean resumed;
  private boolean pauseReached;
  private boolean endOfOutputRead;
  private boolean destroyedForcibly;

  @Builder
  private ScriptedProcess(
      byte @NonNull [] output,
      int exitCode,
      ExitTiming exitTiming,
      Integer pauseAfter,
      Integer failReadAfter,
      boolean resumesOnQuit,
      String stderr) {
    this.output = output.clone();
    this.exitCode = exitCode;
    this.exitTiming = Objects.requireNonNullElse(exitTiming, ExitTiming.AT_END_OF_OUTPUT);
    this.pauseOffset = optionalOffset(pauseAfter);
    this.readFailureOffset = optionalOffset(failReadAfter);
    this.resumesOnQuit = resumesOnQuit;
    this.stderr = Objects.requireNonNullElse(stderr, "").getBytes(StandardCharsets.UTF_8);
    this.endOffset = this.output.length;
    if (this.exitTiming == ExitTiming.AT_LAUNCH) {
      exit.complete(exitCode);
    }
  }

  private static OptionalInt optionalOffset(Integer offset) {
    return offset == null ? OptionalInt.empty() : OptionalInt.of(offset);
  }

  /** Continues the output past its pause. */
  public synchronized void resume() {
    resumed = true;
    notifyAll();
  }

  /** Exits with the scripted exit code; the output keeps whatever the reader has not read. */
  public void exit() {
    exit.complete(exitCode);
  }

  public synchronized boolean hasReachedPause() {
    return pauseReached;
  }

  public synchronized boolean hasReadToEndOfOutput() {
    return endOfOutputRead;
  }

  public synchronized boolean wasDestroyedForcibly() {
    return destroyedForcibly;
  }

  /** Everything written to standard input, as text. */
  public String stdinText() {
    synchronized (stdin) {
      return stdin.toString(StandardCharsets.UTF_8);
    }
  }

  @Override
  public OutputStream getOutputStream() {
    return new ScriptedInput();
  }

  @Override
  public InputStream getInputStream() {
    return stdout;
  }

  @Override
  public InputStream getErrorStream() {
    return new ByteArrayInputStream(stderr);
  }

  @Override
  public int waitFor() throws InterruptedException {
    try {
      return exit.get();
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    try {
      exit.get(timeout, unit);
      return true;
    } catch (TimeoutException _) {
      return false;
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public CompletableFuture<Process> onExit() {
    return exit.thenApply(_ -> this);
  }

  @Override
  public int exitValue() {
    if (!exit.isDone()) {
      throw new IllegalThreadStateException("process has not exited");
    }

    return exit.join();
  }

  @Override
  public boolean isAlive() {
    return !exit.isDone();
  }

  @Override
  public long pid() {
    return PROCESS_ID;
  }

  @Override
  public void destroy() {
    destroyForcibly();
  }

  @Override
  public synchronized Process destroyForcibly() {
    destroyedForcibly = true;
    endOffset = position;
    notifyAll();
    exit.complete(FORCIBLY_DESTROYED_EXIT_CODE);
    return this;
  }

  private synchronized int read(byte[] buffer, int offset, int length) throws IOException {
    awaitResumeAtPause();
    if (readFailureOffset.equals(OptionalInt.of(position))) {
      throw new IOException("scripted read failure at byte " + position);
    }

    if (position >= endOffset) {
      reachEndOfOutput();
      return -1;
    }

    var readable = Math.min(length, readLimit() - position);
    System.arraycopy(output, position, buffer, offset, readable);
    position += readable;
    return readable;
  }

  private void awaitResumeAtPause() throws InterruptedIOException {
    while (isPausedAt(position)) {
      pauseReached = true;
      try {
        wait();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted at the scripted pause");
      }
    }
  }

  private boolean isPausedAt(int offset) {
    return !resumed && offset < endOffset && pauseOffset.equals(OptionalInt.of(offset));
  }

  private int readLimit() {
    var limit = endOffset;
    if (!resumed && pauseOffset.isPresent() && pauseOffset.getAsInt() > position) {
      limit = Math.min(limit, pauseOffset.getAsInt());
    }

    if (readFailureOffset.isPresent() && readFailureOffset.getAsInt() > position) {
      limit = Math.min(limit, readFailureOffset.getAsInt());
    }

    return limit;
  }

  private void reachEndOfOutput() {
    endOfOutputRead = true;
    if (exitTiming == ExitTiming.AT_END_OF_OUTPUT) {
      exit.complete(exitCode);
    }
  }

  private final class ScriptedOutput extends InputStream {

    @Override
    public int read() throws IOException {
      var single = new byte[1];
      var read = read(single, 0, 1);
      return read < 0 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }

      return ScriptedProcess.this.read(buffer, offset, length);
    }
  }

  private final class ScriptedInput extends OutputStream {

    @Override
    public void write(int value) throws IOException {
      if (exit.isDone()) {
        throw new IOException("Broken pipe");
      }

      synchronized (stdin) {
        stdin.write(value);
      }

      if (value == 'q' && resumesOnQuit) {
        resume();
      }
    }
  }
}
