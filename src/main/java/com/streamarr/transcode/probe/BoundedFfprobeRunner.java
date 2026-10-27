package com.streamarr.transcode.probe;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Runs ffprobe; empty when it prints more than {@code maximumOutputBytes}. */
@FunctionalInterface
interface BoundedFfprobeRunner {
  Optional<ProcessOutput> run(List<String> arguments, int maximumOutputBytes)
      throws ExecutionException, InterruptedException;
}
