package com.streamarr.transcode.engine;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stops a child process with SIGSTOP and continues it with SIGCONT, through one Linux pidfd that
 * the pause holds until it resumes. The pidfd keeps naming the child even when its pid is reused,
 * and the resume needs no new file descriptor. Where no pidfd names the child, on another system or
 * when the kernel opens none, the process keeps running: a signal by pid could stop a process that
 * took the pid of a child that has just exited.
 */
final class ProcessSignals {

  private static final boolean LINUX =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

  // The same numbers on every Linux architecture that the worker image targets.
  private static final long SYS_PIDFD_SEND_SIGNAL = 424;
  private static final long SYS_PIDFD_OPEN = 434;
  private static final long SIGCONT = 18;
  private static final long SIGSTOP = 19;

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

  private ProcessSignals() {}

  /**
   * Stops the process unless it has exited or no pidfd names it; the returned pause continues it.
   */
  static Optional<PausedProcess> stop(Process process) {
    if (!LINUX) {
      return Optional.empty();
    }

    return stopThroughPidfd(process);
  }

  // A child whose handle is alive after the pidfd opened still held its pid then, so the pidfd
  // names the child. The handle compares the process's start time, which Process.isAlive does not.
  private static Optional<PausedProcess> stopThroughPidfd(Process process) {
    var pidfd = openPidfd(process.pid());
    if (pidfd < 0) {
      return Optional.empty();
    }

    if (!process.toHandle().isAlive()) {
      close(pidfd);
      return Optional.empty();
    }

    if (sendThroughPidfd(pidfd, SIGSTOP) < 0) {
      close(pidfd);
      return Optional.empty();
    }

    return Optional.of(new PidfdPause(pidfd));
  }

  @SuppressWarnings("java:S1181") // MethodHandle.invokeExact declares Throwable.
  private static long openPidfd(long pid) {
    try {
      return (long) PIDFD_OPEN.invokeExact(SYS_PIDFD_OPEN, pid, 0L);
    } catch (Throwable e) {
      throw unchecked(e);
    }
  }

  // Negative when the child has exited or the kernel refuses the signal.
  @SuppressWarnings("java:S1181") // MethodHandle.invokeExact declares Throwable.
  private static long sendThroughPidfd(long pidfd, long signal) {
    try {
      return (long) PIDFD_SEND_SIGNAL.invokeExact(SYS_PIDFD_SEND_SIGNAL, pidfd, signal, 0L, 0L);
    } catch (Throwable e) {
      throw unchecked(e);
    }
  }

  @SuppressWarnings("java:S1181") // MethodHandle.invokeExact declares Throwable.
  private static void close(long pidfd) {
    try {
      int _ = (int) CLOSE.invokeExact(Math.toIntExact(pidfd));
    } catch (Throwable e) {
      throw unchecked(e);
    }
  }

  private static RuntimeException unchecked(Throwable e) {
    return switch (e) {
      case RuntimeException runtime -> runtime;
      case Error error -> throw error;
      default -> new IllegalStateException("The signal could not be sent", e);
    };
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

      try {
        // A child that SIGSTOP reached can refuse SIGCONT only by having exited.
        long _ = sendThroughPidfd(pidfd, SIGCONT);
      } finally {
        close(pidfd);
      }
    }
  }

  private static MethodHandle downcall(
      String name, FunctionDescriptor descriptor, Linker.Option... options) {
    var linker = Linker.nativeLinker();
    return linker.downcallHandle(
        linker.defaultLookup().find(name).orElseThrow(), descriptor, options);
  }
}
