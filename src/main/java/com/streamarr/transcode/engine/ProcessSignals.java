package com.streamarr.transcode.engine;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/** Sends a job control signal to a child process through the C library's {@code kill}. */
final class ProcessSignals {

  private static final MethodHandle KILL =
      Linker.nativeLinker()
          .downcallHandle(
              Linker.nativeLinker().defaultLookup().find("kill").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

  private static final boolean DARWIN =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

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
    if (!process.isAlive()) {
      return;
    }

    // kill(2) fails only for a child that has exited since the check, which needs no signal.
    try {
      int _ = (int) KILL.invokeExact(Math.toIntExact(process.pid()), signal.number());
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("kill(2) could not be called", e);
    }
  }
}
