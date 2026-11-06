package com.streamarr.transcode.engine;

/** A process that a launcher stopped, which continues once resumed. */
@FunctionalInterface
public interface PausedProcess {

  /** Continues the process; does nothing once it has exited. */
  void resume();
}
