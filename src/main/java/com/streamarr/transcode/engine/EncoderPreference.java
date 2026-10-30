package com.streamarr.transcode.engine;

import java.util.Comparator;

/**
 * The fixed order in which the worker prefers the usable encoders of one codec family, so that a
 * host always resolves a codec family to the same encoder backend.
 */
final class EncoderPreference {

  static final Comparator<WorkerVerifiedEncoder> ORDER =
      Comparator.comparing((WorkerVerifiedEncoder encoder) -> !encoder.backend().hardware())
          .thenComparing(encoder -> encoder.backend().encoder());

  private EncoderPreference() {}
}
