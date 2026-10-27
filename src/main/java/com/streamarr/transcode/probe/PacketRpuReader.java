package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.Presence;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Finds the Dolby Vision RPUs in the {@link FirstPacketSamples} of a stream. */
final class PacketRpuReader {

  private PacketRpuReader() {}

  /**
   * The RPUs that the packets show; empty when the probe read no packet, cannot parse one, or finds
   * no extradata to tell how the samples delimit their NAL units. A sample whose NAL units the
   * reader cannot delimit to its end can hide an RPU, so it rules out no RPU.
   */
  static Optional<RpuObservation> read(JsonNode stream, NalSyntax syntax) {
    return FirstPacketSamples.read(stream, syntax).flatMap(samples -> observation(samples, syntax));
  }

  private static Optional<RpuObservation> observation(List<NalUnits> samples, NalSyntax syntax) {
    var rpus = samples.stream().flatMap(sample -> sample.units().stream()).filter(syntax::isRpu);
    var firstRpu = rpus.findFirst();
    var isEverySampleComplete = samples.stream().allMatch(NalUnits::complete);
    if (firstRpu.isEmpty() && !isEverySampleComplete) {
      return Optional.empty();
    }

    if (firstRpu.isEmpty()) {
      return Optional.of(
          new RpuObservation(
              Presence.PRESENCE_ABSENT, Optional.empty(), Presence.PRESENCE_UNSPECIFIED));
    }

    var layouts = samples.stream().map(sample -> layout(sample, syntax)).toList();
    var startsSample = Presence.PRESENCE_ABSENT;
    if (layouts.contains(Layout.UNKNOWN)) {
      startsSample = Presence.PRESENCE_UNSPECIFIED;
    }

    if (layouts.contains(Layout.RPU_FIRST)) {
      startsSample = Presence.PRESENCE_PRESENT;
    }

    return Optional.of(
        new RpuObservation(
            Presence.PRESENCE_PRESENT, header(firstRpu.get(), syntax), startsSample));
  }

  /**
   * Whether an RPU comes ahead of the sample's first slice, or the sample holds no slice. Unknown
   * when the reader could not delimit the sample to its end and found neither ahead of the cut.
   */
  private static Layout layout(NalUnits sample, NalSyntax syntax) {
    for (var unit : sample.units()) {
      if (syntax.isRpu(unit)) {
        return Layout.RPU_FIRST;
      }

      if (syntax.isPicture(unit)) {
        return Layout.PICTURE_FIRST;
      }
    }

    return sample.complete() ? Layout.PICTURE_FIRST : Layout.UNKNOWN;
  }

  private static Optional<DolbyVisionRpuHeader> header(byte[] rpu, NalSyntax syntax) {
    return DolbyVisionRpuHeaderReader.read(
        Arrays.copyOfRange(rpu, syntax.payloadOffset(), rpu.length));
  }

  private enum Layout {
    RPU_FIRST,
    PICTURE_FIRST,
    UNKNOWN
  }
}
