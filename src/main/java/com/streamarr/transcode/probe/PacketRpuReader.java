package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.Presence;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

/** Finds the Dolby Vision RPUs in the {@link FirstPacketSamples} of a stream. */
final class PacketRpuReader {

  private PacketRpuReader() {}

  /**
   * The RPUs that the packets show; empty when the probe read no packet, cannot parse one, or finds
   * no extradata to tell how the samples delimit their NAL units. A sample whose NAL units the
   * reader cannot delimit to its end can hide an RPU, and so can units that the walks miss, as
   * {@link WalkCoverage} says, so they rule out no RPU.
   */
  static Optional<RpuObservation> read(JsonNode stream, NalSyntax syntax) {
    return FirstPacketSamples.read(stream, syntax)
        .flatMap(samples -> observation(samples, syntax, stream));
  }

  private static Optional<RpuObservation> observation(
      List<NalUnits> samples, NalSyntax syntax, JsonNode stream) {
    var isEveryUnitWalked = !WalkCoverage.missesUnits(stream);
    var isEverySampleDelimitable = !WalkCoverage.missesPacketUnits(stream);
    var rpus = samples.stream().flatMap(sample -> sample.units().stream()).filter(syntax::isRpu);
    var firstRpu = rpus.findFirst();
    var isEverySampleComplete = samples.stream().allMatch(NalUnits::complete);
    if (firstRpu.isEmpty() && !(isEverySampleComplete && isEveryUnitWalked)) {
      return Optional.empty();
    }

    if (firstRpu.isEmpty()) {
      return Optional.of(
          new RpuObservation(
              Presence.PRESENCE_ABSENT,
              Optional.empty(),
              DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED));
    }

    var placement = DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED;
    if (isEverySampleComplete && isEverySampleDelimitable) {
      placement = placement(samples.stream().map(sample -> Layout.of(sample, syntax)).toList());
    }

    return Optional.of(
        new RpuObservation(Presence.PRESENCE_PRESENT, header(firstRpu.get(), syntax), placement));
  }

  /**
   * The placement whose rule the samples meet, in order; unspecified when a sample holds more than
   * one RPU, a sample ahead of the first picture's sample holds one, or the samples meet neither
   * rule. At least one sample holds an RPU, so when no sample holds a picture, an RPU lies ahead of
   * the pictures.
   */
  private static DolbyVisionRpuPlacement placement(List<Layout> layouts) {
    var isAnyRpuAheadOfThePictures =
        layouts.stream().takeWhile(layout -> !layout.hasPicture()).anyMatch(Layout::hasRpu);
    if (isAnyRpuAheadOfThePictures || layouts.stream().anyMatch(layout -> layout.rpus() > 1)) {
      return DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED;
    }

    var withRpus = layouts.stream().filter(Layout::hasRpu).toList();
    if (withRpus.stream().allMatch(Layout::rpuEndsPicture)) {
      return DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE;
    }

    if (withRpus.stream().allMatch(Layout::startsWithRpu)) {
      return DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE;
    }

    return DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED;
  }

  private static Optional<DolbyVisionRpuHeader> header(byte[] rpu, NalSyntax syntax) {
    return DolbyVisionRpuHeaderReader.read(
        Arrays.copyOfRange(rpu, syntax.payloadOffset(), rpu.length));
  }

  /**
   * Where a sample, which the reader delimited to its end, holds its RPU. A sample starts with an
   * RPU when its only RPU comes ahead of its first slice, or it holds an RPU and no slice. The RPU
   * ends the sample's picture when it follows a slice as the sample's last NAL unit.
   */
  @Builder
  private record Layout(
      int rpus, boolean hasPicture, boolean startsWithRpu, boolean rpuEndsPicture) {

    static Layout of(NalUnits sample, NalSyntax syntax) {
      var units = sample.units();
      var rpuIndexes =
          IntStream.range(0, units.size())
              .filter(index -> syntax.isRpu(units.get(index)))
              .toArray();
      var firstSlice =
          IntStream.range(0, units.size())
              .filter(index -> syntax.isPicture(units.get(index)))
              .findFirst();
      if (rpuIndexes.length != 1) {
        return Layout.builder().rpus(rpuIndexes.length).hasPicture(firstSlice.isPresent()).build();
      }

      var rpu = rpuIndexes[0];
      var isAheadOfTheSlices = firstSlice.isEmpty() || rpu < firstSlice.getAsInt();
      var isLast = rpu == units.size() - 1;
      return Layout.builder()
          .rpus(1)
          .hasPicture(firstSlice.isPresent())
          .startsWithRpu(isAheadOfTheSlices)
          .rpuEndsPicture(!isAheadOfTheSlices && isLast)
          .build();
    }

    boolean hasRpu() {
      return rpus > 0;
    }
  }
}
