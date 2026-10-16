package com.streamarr.transcode.worker.support;

import com.streamarr.transcode.engine.ProcessLauncher;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Launches FFmpeg through another launcher and keeps a copy of everything FFmpeg writes to its
 * standard output as the worker reads it, so a test can compare the worker's uploads with FFmpeg's
 * own output of that very run.
 */
public final class TeeingProcessLauncher implements ProcessLauncher {

  private final ProcessLauncher launcher;
  private final Map<UUID, ByteArrayOutputStream> outputs = new ConcurrentHashMap<>();

  public TeeingProcessLauncher(ProcessLauncher launcher) {
    this.launcher = launcher;
  }

  @Override
  public Process launch(List<String> command, UUID jobAttemptId) throws IOException {
    var copy = new ByteArrayOutputStream();
    outputs.put(jobAttemptId, copy);
    return new TeedProcess(launcher.launch(command, jobAttemptId), copy);
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

    private TeedProcess(Process process, ByteArrayOutputStream copy) {
      this.process = process;
      this.output = new TeeInputStream(process.getInputStream(), copy);
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

    private TeeInputStream(InputStream input, ByteArrayOutputStream copy) {
      super(input);
      this.copy = copy;
    }

    @Override
    public int read() throws IOException {
      var value = super.read();
      if (value >= 0) {
        synchronized (copy) {
          copy.write(value);
        }
      }

      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      var read = super.read(buffer, offset, length);
      if (read > 0) {
        synchronized (copy) {
          copy.write(buffer, offset, read);
        }
      }

      return read;
    }
  }
}
