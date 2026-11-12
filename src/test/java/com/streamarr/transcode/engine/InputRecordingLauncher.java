package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Launches real FFmpeg and keeps every byte that the worker writes to each FFmpeg's standard input.
 * A process's output reports its end only once its error output has ended, so the worker reads
 * every line of the error output before it learns that FFmpeg exited, however fast FFmpeg runs.
 */
final class InputRecordingLauncher implements ProcessLauncher {

  private final ProcessLauncher launcher = new ProcessBuilderLauncher();
  private final ByteArrayOutputStream input = new ByteArrayOutputStream();

  @Override
  public Process launch(List<String> command, UUID jobAttemptId) throws IOException {
    return new RecordedProcess(launcher.launch(command, jobAttemptId));
  }

  /** Every byte that the worker wrote to the standard input of the processes, as ASCII. */
  String input() {
    synchronized (input) {
      return input.toString(StandardCharsets.US_ASCII);
    }
  }

  private final class RecordedProcess extends Process {

    private final Process process;
    private final CountDownLatch errorOutputEnded = new CountDownLatch(1);
    private final InputStream output;
    private final InputStream errorOutput;
    private final OutputStream standardInput;

    private RecordedProcess(Process process) {
      this.process = process;
      this.output = new OutputEndingAfterErrorOutput(process.getInputStream());
      this.errorOutput = new ErrorOutputSignallingItsEnd(process.getErrorStream());
      this.standardInput = new RecordedInput(process.getOutputStream());
    }

    @Override
    public OutputStream getOutputStream() {
      return standardInput;
    }

    @Override
    public InputStream getInputStream() {
      return output;
    }

    @Override
    public InputStream getErrorStream() {
      return errorOutput;
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

    private final class OutputEndingAfterErrorOutput extends FilterInputStream {

      private OutputEndingAfterErrorOutput(InputStream output) {
        super(output);
      }

      @Override
      public int read() throws IOException {
        var value = super.read();
        if (value < 0) {
          awaitTheErrorOutputsEnd();
        }

        return value;
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        var read = super.read(buffer, offset, length);
        if (read < 0) {
          awaitTheErrorOutputsEnd();
        }

        return read;
      }

      private void awaitTheErrorOutputsEnd() throws IOException {
        try {
          if (!errorOutputEnded.await(1, TimeUnit.MINUTES)) {
            throw new IOException("FFmpeg's error output did not end within a minute");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("Interrupted while the error output was read");
        }
      }
    }

    private final class ErrorOutputSignallingItsEnd extends FilterInputStream {

      private ErrorOutputSignallingItsEnd(InputStream errorOutput) {
        super(errorOutput);
      }

      @Override
      public int read() throws IOException {
        var value = super.read();
        if (value < 0) {
          errorOutputEnded.countDown();
        }

        return value;
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        var read = super.read(buffer, offset, length);
        if (read < 0) {
          errorOutputEnded.countDown();
        }

        return read;
      }

      @Override
      public void close() throws IOException {
        errorOutputEnded.countDown();
        super.close();
      }
    }
  }

  private final class RecordedInput extends FilterOutputStream {

    private RecordedInput(OutputStream standardInput) {
      super(standardInput);
    }

    @Override
    public void write(int value) throws IOException {
      synchronized (input) {
        input.write(value);
      }

      super.write(value);
    }
  }
}
