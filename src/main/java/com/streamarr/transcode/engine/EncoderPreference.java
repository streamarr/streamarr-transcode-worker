package com.streamarr.transcode.engine;

import java.util.Comparator;
import java.util.List;

/**
 * The fixed order in which the worker prefers the usable encoders of one codec family, so that a
 * host always resolves a codec family to the same encoder backend. A variant's replacement attempts
 * keep the encoder backend of its first job attempt, so they depend on that.
 *
 * <p>A keyframe-verified hardware encoder comes first, then the other hardware encoders, and the
 * software encoder last. Among equally verified hardware encoders the hardware API decides: NVENC,
 * QSV, AMF, VideoToolbox, then VAAPI.
 */
final class EncoderPreference {

  // FFmpeg names each hardware encoder after its codec family and its API, such as h264_nvenc.
  // VAAPI comes last because it is a generic API over the chips that QSV and AMF drive directly.
  private static final List<String> HARDWARE_APIS =
      List.of("nvenc", "qsv", "amf", "videotoolbox", "vaapi");

  static final Comparator<WorkerVerifiedEncoder> ORDER =
      Comparator.comparing((WorkerVerifiedEncoder encoder) -> !encoder.backend().hardware())
          .thenComparing(encoder -> !encoder.keyframeVerified())
          .thenComparing(encoder -> hardwareApiRank(encoder.backend().encoder()))
          .thenComparing(encoder -> encoder.backend().encoder());

  private EncoderPreference() {}

  // An API this list does not name ranks after every listed one; the name then decides.
  private static int hardwareApiRank(String encoder) {
    var api = encoder.substring(encoder.indexOf('_') + 1);
    var rank = HARDWARE_APIS.indexOf(api);
    if (rank < 0) {
      return HARDWARE_APIS.size();
    }

    return rank;
  }
}
