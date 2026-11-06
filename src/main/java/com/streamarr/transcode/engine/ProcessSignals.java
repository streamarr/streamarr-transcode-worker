package com.streamarr.transcode.engine;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Sends a job control signal to a child process. On Linux the signal travels through a pidfd, which
 * keeps naming the child even when its pid is reused. Elsewhere, and where the kernel opens no
 * pidfd, it travels by pid while the child is alive, which leaves the window that {@link
 * Process#destroy} also leaves between its check and its signal.
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

  enum Signal {
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

  /** Signals the process unless it has exited. */
  @SuppressWarnings("java:S1181") // MethodHandle.invokeExact declares Throwable.
  static void send(Process process, Signal signal) {
    try {
      if (!DARWIN && isSentThroughPidfd(process, signal)) {
        return;
      }

      sendByPid(process, signal);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("The signal could not be sent", e);
    }
  }

  // False when the kernel opened no pidfd, as for a child that it has already reaped. A child whose
  // handle is alive after the pidfd opened still held its pid then, so the pidfd names the child.
  // The handle compares the process's start time, which Process.isAlive does not.
  private static boolean isSentThroughPidfd(Process process, Signal signal) throws Throwable {
    var pidfd = (long) PIDFD_OPEN.invokeExact(SYS_PIDFD_OPEN, process.pid(), 0L);
    if (pidfd < 0) {
      return false;
    }

    try {
      if (process.toHandle().isAlive()) {
        long _ =
            (long)
                PIDFD_SEND_SIGNAL.invokeExact(
                    SYS_PIDFD_SEND_SIGNAL, pidfd, (long) signal.number(), 0L, 0L);
      }

      return true;
    } finally {
      int _ = (int) CLOSE.invokeExact(Math.toIntExact(pidfd));
    }
  }

  // kill(2) fails only for a child that has exited since the check, which needs no signal.
  private static void sendByPid(Process process, Signal signal) throws Throwable {
    if (!process.toHandle().isAlive()) {
      return;
    }

    int _ = (int) KILL.invokeExact(Math.toIntExact(process.pid()), signal.number());
  }

  private static MethodHandle downcall(
      String name, FunctionDescriptor descriptor, Linker.Option... options) {
    var linker = Linker.nativeLinker();
    return linker.downcallHandle(
        linker.defaultLookup().find(name).orElseThrow(), descriptor, options);
  }
}
