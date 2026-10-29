package com.streamarr.transcode.fakes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * A process whose standard output replays scripted bytes, standing in for FFmpeg.
 *
 * <p>The output can pause at one offset until the test or a {@code q} on standard input resumes it,
 * and can fail at another, with an I/O error or with the unchecked exception the test supplies. The
 * process exits at the end of its output, at launch, on {@code q}, or when the test says so. A
 * termination, like SIGTERM, ends the output where it stands and exits with 143 unless the process
 * ignores termination, as a hung FFmpeg does. A forcible end, like SIGKILL, ends the output and
 * exits with 137, or lingers until the test says it exits.
 *
 * <p>Like the JDK's process on Unix, {@link #destroy()} and {@link #destroyForcibly()} also close
 * the process's standard input, output and error: a read of either output then fails with "Stream
 * closed", at once or once a read that waits for more output ends, and the close of standard input
 * waits for a write in progress. Its {@link #toHandle() handle} only signals the process. Standard
 * error stays open until the process exits, and standard input holds at most the capacity the test
 * gives it, as a pipe to a process that reads nothing does.
 */
public final class ScriptedProcess extends Process {

  private static final int TERMINATED_EXIT_CODE = 143;
  private static final int FORCIBLY_DESTROYED_EXIT_CODE = 137;

  private static final long PROCESS_ID = 4242;

  /** When the process exits relative to the reader reaching the end of its output. */
  public enum ExitTiming {
    /** Once the reader has read every byte, as FFmpeg closes its output when it exits. */
    AT_END_OF_OUTPUT,
    /** Immediately, with the whole output still unread, as if held in the pipe. */
    AT_LAUNCH,
    /** Only when the test calls {@link #exit()}. */
    WHEN_TEST_EXITS,
    /**
     * When {@code q} arrives on standard input, as FFmpeg quits; the output keeps whatever the
     * reader has not read.
     */
    AT_QUIT,
    /**
     * Once standard input is closed and the reader has read every byte, as an FFmpeg that reads its
     * media from standard input finishes; the close also continues the output past its pause.
     */
    AT_END_OF_INPUT
  }

  private final byte[] output;
  private final int exitCode;
  private final ExitTiming exitTiming;
  private final OptionalInt pauseOffset;
  private final OptionalInt readFailureOffset;
  private final Optional<RuntimeException> uncheckedReadFailure;
  private final boolean resumesOnQuit;
  private final boolean ignoresTermination;
  private final boolean lingersAfterKill;
  private final boolean readsMediaFromStdin;
  private final OptionalInt stdinCapacity;
  private final byte[] stderr;
  private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
  private final CompletableFuture<Integer> exit = new CompletableFuture<>();
  private final CompletableFuture<Void> pauseReached = new CompletableFuture<>();
  private final CompletableFuture<Void> inputBlocked = new CompletableFuture<>();
  private final CompletableFuture<Void> exitAwaited = new CompletableFuture<>();
  private final InputStream stdout = new ScriptedOutput();
  private final InputStream errorOutput = new ScriptedErrorOutput();
  private final ScriptedInput stdinStream = new ScriptedInput();

  private int position;
  private int errorPosition;
  private boolean streamsClosed;
  private boolean errorOutputClosed;
  private int endOffset;
  private boolean resumed;
  private boolean endOfOutputRead;
  private boolean terminated;
  private boolean destroyedForcibly;
  private boolean inputEnded;
  private volatile Thread reader;

  @Builder
  private ScriptedProcess(
      byte @NonNull [] output,
      int exitCode,
      ExitTiming exitTiming,
      Integer pauseAfter,
      Integer failReadAfter,
      RuntimeException failReadWith,
      boolean resumesOnQuit,
      boolean ignoresTermination,
      boolean lingersAfterKill,
      boolean readsMediaFromStdin,
      Integer stdinCapacity,
      String stderr) {
    this.output = output.clone();
    this.exitCode = exitCode;
    this.exitTiming = Objects.requireNonNullElse(exitTiming, ExitTiming.AT_END_OF_OUTPUT);
    this.pauseOffset = optionalOffset(pauseAfter);
    this.readFailureOffset = optionalOffset(failReadAfter);
    this.uncheckedReadFailure = Optional.ofNullable(failReadWith);
    this.resumesOnQuit = resumesOnQuit;
    this.ignoresTermination = ignoresTermination;
    this.lingersAfterKill = lingersAfterKill;
    this.readsMediaFromStdin = readsMediaFromStdin;
    this.stdinCapacity = optionalOffset(stdinCapacity);
    this.stderr = Objects.requireNonNullElse(stderr, "").getBytes(StandardCharsets.UTF_8);
    this.endOffset = this.output.length;
    if (this.exitTiming == ExitTiming.AT_LAUNCH) {
      exit.complete(exitCode);
    }

    exit.whenComplete((_, _) -> wakeWaiters());
  }

  private synchronized void wakeWaiters() {
    notifyAll();
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

  /**
   * How many bytes of its output the reader has taken. A pipe holds only a bounded amount of
   * FFmpeg's output that nobody has read, so FFmpeg blocks once the reader stops taking bytes.
   */
  public synchronized int bytesTaken() {
    return position;
  }

  /** The thread that last read the output, as FFmpeg's pipe would see it. */
  public Optional<Thread> reader() {
    return Optional.ofNullable(reader);
  }

  /** Completes once the reader reaches the pause, where it waits before any other call proceeds. */
  public CompletableFuture<Void> pauseReached() {
    return pauseReached.copy();
  }

  public synchronized boolean hasReadToEndOfOutput() {
    return endOfOutputRead;
  }

  /** Whether something asked the process to terminate, as SIGTERM does. */
  public synchronized boolean wasTerminated() {
    return terminated;
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

  /** Everything written to standard input. */
  public byte[] stdinBytes() {
    synchronized (stdin) {
      return stdin.toByteArray();
    }
  }

  /** Completes once a write to standard input waits because the input holds its capacity. */
  public CompletableFuture<Void> inputBlocked() {
    return inputBlocked.copy();
  }

  /** Completes once something waits for the process to exit without a bound. */
  public CompletableFuture<Void> exitAwaited() {
    return exitAwaited.copy();
  }

  /** Whether something closed standard input, as the end of FFmpeg's piped media input. */
  public synchronized boolean hasInputEnded() {
    return inputEnded;
  }

  @Override
  public OutputStream getOutputStream() {
    return stdinStream;
  }

  @Override
  public InputStream getInputStream() {
    return stdout;
  }

  @Override
  public InputStream getErrorStream() {
    return errorOutput;
  }

  @Override
  public int waitFor() throws InterruptedException {
    exitAwaited.complete(null);
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
    synchronized (this) {
      terminate();
      closeErrorOutput();
    }

    closeStreams();
  }

  @Override
  public Process destroyForcibly() {
    synchronized (this) {
      kill();
      closeErrorOutput();
    }

    closeStreams();
    return this;
  }

  // The JDK's destroy signals the process and then closes its streams, usually before its reaper
  // sees the exit. A reader waiting for more error output then fails with "Stream closed" instead
  // of reaching the end, so the fake closes the error output together with the signal.
  private void closeErrorOutput() {
    errorOutputClosed = true;
    notifyAll();
  }

  private synchronized void terminate() {
    terminated = true;
    if (ignoresTermination) {
      return;
    }

    endOutput();
    exit.complete(TERMINATED_EXIT_CODE);
  }

  private synchronized void kill() {
    destroyedForcibly = true;
    endOutput();
    if (!lingersAfterKill) {
      exit.complete(FORCIBLY_DESTROYED_EXIT_CODE);
    }
  }

  // Standard input closes first, and its close waits for a write in progress, as the JDK's does.
  private void closeStreams() {
    stdinStream.close();
    synchronized (this) {
      streamsClosed = true;
      notifyAll();
    }
  }

  /** A handle whose termination and destruction act on this process as its own methods do. */
  @Override
  public ProcessHandle toHandle() {
    return new ScriptedHandle();
  }

  private final class ScriptedHandle implements ProcessHandle {

    @Override
    public long pid() {
      return PROCESS_ID;
    }

    @Override
    public Optional<ProcessHandle> parent() {
      return Optional.empty();
    }

    @Override
    public Stream<ProcessHandle> children() {
      return Stream.empty();
    }

    @Override
    public Stream<ProcessHandle> descendants() {
      return Stream.empty();
    }

    @Override
    public Info info() {
      throw new UnsupportedOperationException("a scripted process has no process information");
    }

    @Override
    public CompletableFuture<ProcessHandle> onExit() {
      return exit.thenApply(_ -> this);
    }

    @Override
    public boolean supportsNormalTermination() {
      return true;
    }

    @Override
    public boolean destroy() {
      terminate();
      return true;
    }

    @Override
    public boolean destroyForcibly() {
      kill();
      return true;
    }

    @Override
    public boolean isAlive() {
      return ScriptedProcess.this.isAlive();
    }

    @Override
    public int compareTo(ProcessHandle other) {
      return Long.compare(pid(), other.pid());
    }
  }

  private void endOutput() {
    endOffset = position;
    notifyAll();
  }

  private synchronized int read(byte[] buffer, int offset, int length) throws IOException {
    awaitResumeAtPause();
    if (streamsClosed) {
      throw streamClosed();
    }

    if (readFailureOffset.equals(OptionalInt.of(position))) {
      throw readFailure();
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

  private IOException readFailure() {
    uncheckedReadFailure.ifPresent(
        failure -> {
          throw failure;
        });
    return new IOException("scripted read failure at byte " + position);
  }

  private static IOException streamClosed() {
    return new IOException("Stream closed");
  }

  // A read that waits at the pause of a process whose streams were closed ends once it exits.
  private void awaitResumeAtPause() throws InterruptedIOException {
    while (isPausedAt(position) && !(streamsClosed && exit.isDone())) {
      pauseReached.complete(null);
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
    if (exitTiming == ExitTiming.AT_END_OF_OUTPUT
        || (exitTiming == ExitTiming.AT_END_OF_INPUT && inputEnded)) {
      exit.complete(exitCode);
    }
  }

  private synchronized int readErrorOutput(byte[] buffer, int offset, int length)
      throws IOException {
    while (!errorOutputClosed && errorPosition >= stderr.length && !exit.isDone()) {
      awaitChange("interrupted while reading the scripted error output");
    }

    if (errorOutputClosed) {
      throw streamClosed();
    }

    if (errorPosition >= stderr.length) {
      return -1;
    }

    var readable = Math.min(length, stderr.length - errorPosition);
    System.arraycopy(stderr, errorPosition, buffer, offset, readable);
    errorPosition += readable;
    return readable;
  }

  private void awaitChange(String interruption) throws InterruptedIOException {
    try {
      wait();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException(interruption);
    }
  }

  private synchronized void endInput() {
    inputEnded = true;
    if (exitTiming != ExitTiming.AT_END_OF_INPUT) {
      return;
    }

    resume();
    if (endOfOutputRead) {
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

      reader = Thread.currentThread();
      return ScriptedProcess.this.read(buffer, offset, length);
    }
  }

  private final class ScriptedErrorOutput extends InputStream {

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

      return readErrorOutput(buffer, offset, length);
    }
  }

  // The lock stands for the one the JDK's stream holds through a write, which its close waits for.
  private final class ScriptedInput extends OutputStream {

    private final ReentrantLock writing = new ReentrantLock();
    private boolean closed;

    @Override
    public void write(int value) throws IOException {
      writing.lock();
      try {
        awaitRoom();
        synchronized (stdin) {
          stdin.write(value);
        }
      } finally {
        writing.unlock();
      }

      if (value != 'q' || readsMediaFromStdin) {
        return;
      }

      if (resumesOnQuit) {
        resume();
      }

      if (exitTiming == ExitTiming.AT_QUIT) {
        exit();
      }
    }

    private void awaitRoom() throws IOException {
      synchronized (ScriptedProcess.this) {
        while (!closed && !exit.isDone() && isFull()) {
          inputBlocked.complete(null);
          awaitChange("interrupted while writing to the scripted standard input");
        }

        if (closed) {
          throw streamClosed();
        }

        if (exit.isDone()) {
          throw new IOException("Broken pipe");
        }
      }
    }

    private boolean isFull() {
      synchronized (stdin) {
        return stdinCapacity.isPresent() && stdin.size() >= stdinCapacity.getAsInt();
      }
    }

    @Override
    public void close() {
      writing.lock();
      try {
        synchronized (ScriptedProcess.this) {
          closed = true;
        }
      } finally {
        writing.unlock();
      }

      endInput();
    }
  }
}
