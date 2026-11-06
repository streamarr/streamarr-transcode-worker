package com.streamarr.transcode.engine;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stops a child process with SIGSTOP and continues it with SIGCONT. On Linux both signals travel
 * through one pidfd that the pause holds until it resumes, so they keep naming the child even when
 * its pid is reused, and the resume needs no new file descriptor. Elsewhere, and where the kernel
 * opens no pidfd, they travel by pid, which leaves the window that {@link Process#destroy} also
 * leaves between its check and its signal.
 */
final class ProcessSignals {

  private static final boolean DARWIN =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

  // The same numbers on every Linux architecture.
  private static final long SYS_PIDFD_SEND_SIGNAL = 424;
  private static final long SYS_PIDFD_OPEN = 434;

  private static final MethodHandle KILL =
      downcall("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
  private static final MethodHandle CLOSE =
      downcall("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
  // syscall(2) is variadic, and every argument after the number travels as a long.
  private static final MethodHandle PIDFD_OPEN =
      downcall(
          "syscall",
          FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
          Linker.Option.firstVariadicArg(1));
  private static final MethodHandle PIDFD_SEND_SIGNAL =
      downcall(
          "syscall",
          FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
          Linker.Option.firstVariadicArg(1));

  private static final PausedProcess NOTHING_TO_RESUME = () -> {};

  private enum Signal {
    STOP(19, 17),
    CONTINUE(18, 19);

    private final int linuxNumber;
    private final int darwinNumber;

    Signal(int linuxNumber, int darwinNumber) {
      this.linuxNumber = linuxNumber;
      this.darwinNumber = darwinNumber;
    }

    private int number() {
      return DARWIN ? darwinNumber : linuxNumber;
    }
  }

  private ProcessSignals() {}

  /** Stops the process unless it has exited; the returned pause continues it. */
  static PausedProcess stop(Process process) {
    return invoke(() -> DARWIN ? stopByPid(process) : stopThroughPidfd(process));
  }

  // A child whose handle is alive after the pidfd opened still held its pid then, so the pidfd
  // names the child. The handle compares the process's start time, which Process.isAlive does not.
  private static PausedProcess stopThroughPidfd(Process process) throws Throwable {
    var pidfd = (long) PIDFD_OPEN.invokeExact(SYS_PIDFD_OPEN, process.pid(), 0L);
    if (pidfd < 0) {
      return stopByPid(process);
    }

    if (!process.toHandle().isAlive()) {
      close(pidfd);
      return NOTHING_TO_RESUME;
    }

    sendThroughPidfd(pidfd, Signal.STOP);
    return new PidfdPause(pidfd);
  }

  // kill(2) fails only for a child that has exited since the check, which needs no signal. The
  // resume checks only the JDK's own exit record, which needs no file descriptor; a SIGCONT that
  // reaches a process that reused the pid continues a process that runs anyway.
  private static PausedProcess stopByPid(Process process) throws Throwable {
    if (!process.toHandle().isAlive()) {
      return NOTHING_TO_RESUME;
    }

    var pid = Math.toIntExact(process.pid());
    int _ = (int) KILL.invokeExact(pid, Signal.STOP.number());
    return () ->
        invoke(
            () -> {
              if (process.isAlive()) {
                int _ = (int) KILL.invokeExact(pid, Signal.CONTINUE.number());
              }

              return NOTHING_TO_RESUME;
            });
  }

  // pidfd_send_signal(2) fails only for a child that has exited, which needs no signal.
  private static void sendThroughPidfd(long pidfd, Signal signal) throws Throwable {
    long _ =
        (long)
            PIDFD_SEND_SIGNAL.invokeExact(
                SYS_PIDFD_SEND_SIGNAL, pidfd, (long) signal.number(), 0L, 0L);
  }

  private static void close(long pidfd) throws Throwable {
    int _ = (int) CLOSE.invokeExact(Math.toIntExact(pidfd));
  }

  @SuppressWarnings("java:S1181") // MethodHandle.invokeExact declares Throwable.
  private static PausedProcess invoke(NativeCall call) {
    try {
      return call.run();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("The signal could not be sent", e);
    }
  }

  @FunctionalInterface
  private interface NativeCall {
    PausedProcess run() throws Throwable;
  }

  // Resumes once, and closes the pidfd with that resume.
  private static final class PidfdPause implements PausedProcess {

    private final long pidfd;
    private final AtomicBoolean resumed = new AtomicBoolean();

    private PidfdPause(long pidfd) {
      this.pidfd = pidfd;
    }

    @Override
    public void resume() {
      if (resumed.getAndSet(true)) {
        return;
      }

      invoke(
          () -> {
            try {
              sendThroughPidfd(pidfd, Signal.CONTINUE);
            } finally {
              close(pidfd);
            }

            return NOTHING_TO_RESUME;
          });
    }
  }

  private static MethodHandle downcall(
      String name, FunctionDescriptor descriptor, Linker.Option... options) {
    var linker = Linker.nativeLinker();
    return linker.downcallHandle(
        linker.defaultLookup().find(name).orElseThrow(), descriptor, options);
  }
}
