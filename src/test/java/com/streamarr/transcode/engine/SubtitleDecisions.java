package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.OptionalInt;

/** Subtitle decisions that engine tests share. */
final class SubtitleDecisions {

  /** Subtitle streams left out of the output, as every recording's command leaves them. */
  static final SubtitleDecision EXCLUDED =
      new SubtitleDecision(
          SubtitleMode.EXCLUDE, Optional.empty(), OptionalInt.empty(), Optional.empty());

  private SubtitleDecisions() {}
}
