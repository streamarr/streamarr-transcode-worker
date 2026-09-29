package com.streamarr.transcode.worker.support;

import com.streamarr.transcode.engine.ProcessLauncher;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Launches FFmpeg through another launcher and keeps a copy of everything FFmpeg writes to its
 * standard output as the worker reads it, so a test can compare the worker's uploads with FFmpeg's
 * own output of that very run.
 */
public final class TeeingProcessLauncher implements ProcessLauncher {

  private final ProcessLauncher launcher;
  private final Map<UUID, ByteArrayOutputStream> outputs = new ConcurrentHashMap<>();
  private final AtomicReference<Gate> nextGate = new AtomicReference<>(Gate.OPEN);

  public TeeingProcessLauncher(ProcessLauncher launcher) {
    this.launcher = launcher;
  }

  @Override
  public Process launch(List<String> command, UUID jobAttemptId) throws IOException {
    var copy = new ByteArrayOutputStream();
    outputs.put(jobAttemptId, copy);
    var gate = nextGate.getAndSet(Gate.OPEN);
    return new TeedProcess(launcher.launch(command, jobAttemptId), copy, gate);
  }

  /**
   * Withholds the next attempt's output past this many bytes from the worker until the release
   * completes or the limit passes, and then reads on.
   *
   * @return completes with whether the release came before the limit passed
   */
  public CompletableFuture<Boolean> withholdOutputAfter(
      long bytes, CompletableFuture<?> release, Duration limit) {
    var gate = new Gate(bytes, release, limit);
    nextGate.set(gate);
    return gate.releasedInTime;
  }

  /** What the attempt's FFmpeg wrote to its standard output and the worker read. */
  public byte[] outputOf(UUID jobAttemptId) {
    var copy = outputs.get(jobAttemptId);
    synchronized (copy) {
      return copy.toByteArray();
    }
  }

  private static final class TeedProcess extends Process {

    private final Process process;
    private final InputStream output;

    private TeedProcess(Process process, ByteArrayOutputStream copy, Gate gate) {
      this.process = process;
      this.output = new TeeInputStream(process.getInputStream(), copy, gate);
    }

    @Override
    public OutputStream getOutputStream() {
      return process.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return output;
    }

    @Override
    public InputStream getErrorStream() {
      return process.getErrorStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      return process.waitFor();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      return process.waitFor(timeout, unit);
    }

    @Override
    public int exitValue() {
      return process.exitValue();
    }

    @Override
    public void destroy() {
      process.destroy();
    }

    @Override
    public Process destroyForcibly() {
      process.destroyForcibly();
      return this;
    }

    @Override
    public boolean isAlive() {
      return process.isAlive();
    }

    @Override
    public long pid() {
      return process.pid();
    }

    @Override
    public CompletableFuture<Process> onExit() {
      return process.onExit().thenApply(_ -> this);
    }
  }

  private static final class TeeInputStream extends FilterInputStream {

    private final ByteArrayOutputStream copy;
    private final Gate gate;
    private long taken;

    private TeeInputStream(InputStream input, ByteArrayOutputStream copy, Gate gate) {
      super(input);
      this.copy = copy;
      this.gate = gate;
    }

    @Override
    public int read() throws IOException {
      gate.allowed(taken, 1);
      var value = super.read();
      if (value >= 0) {
        taken++;
        synchronized (copy) {
          copy.write(value);
        }
      }

      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      var read = super.read(buffer, offset, gate.allowed(taken, length));
      if (read > 0) {
        taken += read;
        synchronized (copy) {
          copy.write(buffer, offset, read);
        }
      }

      return read;
    }
  }

  // Lets reads through up to the byte limit, then waits for the release or the time limit.
  private static final class Gate {

    private static final Gate OPEN =
        new Gate(Long.MAX_VALUE, new CompletableFuture<>(), Duration.ZERO);

    private final long bytes;
    private final CompletableFuture<?> release;
    private final Duration limit;
    private final CompletableFuture<Boolean> releasedInTime = new CompletableFuture<>();

    private Gate(long bytes, CompletableFuture<?> release, Duration limit) {
      this.bytes = bytes;
      this.release = release;
      this.limit = limit;
    }

    private int allowed(long taken, int length) throws IOException {
      if (releasedInTime.isDone() || taken + length <= bytes) {
        return length;
      }

      if (taken < bytes) {
        return Math.toIntExact(bytes - taken);
      }

      try {
        release.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        releasedInTime.complete(true);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted while withholding FFmpeg's output");
      } catch (ExecutionException | TimeoutException _) {
        releasedInTime.complete(false);
      }

      return length;
    }
  }
}
