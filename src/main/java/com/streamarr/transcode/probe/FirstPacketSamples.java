package com.streamarr.transcode.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The NAL units of the first packets of a stream, as {@link FirstPacketsProbe} adds them, by the
 * codec's {@link NalSyntax}: one sample for each packet. A decoder configuration record means
 * samples of length-prefixed NAL units, and Annex B extradata, as MPEG-TS carries, means start
 * codes.
 */
final class FirstPacketSamples {

  private static final int FEWEST_SPLIT_BYTES = 4;

  private FirstPacketSamples() {}

  /**
   * The samples of the first packets; empty when the probe read no packet, cannot parse one, or
   * finds no extradata to tell how the samples delimit their NAL units.
   */
  static Optional<List<NalUnits>> read(JsonNode stream, NalSyntax syntax) {
    var extradata = Extradata.of(stream);
    if (extradata.isEmpty()) {
      return Optional.empty();
    }

    var lengthSize = syntax.lengthSize(extradata.get());
    return FirstPackets.of(stream)
        .map(
            packets ->
                packets.stream()
                    .map(
                        bytes ->
                            lengthSize.isPresent()
                                ? lengthPrefixed(bytes, lengthSize.get())
                                : NalUnits.annexB(bytes))
                    .map(sample -> reachingDecoder(sample, syntax))
                    .toList());
  }

  /**
   * The NAL units of the stream's first packet, when the copy's Annex B filter and FFmpeg's
   * decoders read the same ones from it; empty otherwise. The filter refuses a sample whose lengths
   * do not end at its end, which the decoders accept by dropping the 1 to 3 bytes that can end it.
   * The copy's record writers read a NAL unit that the decoders drop, such as one whose
   * forbidden_zero_bit is 1, and split the sample again at a start code that a NAL unit holds. A
   * NAL unit of 0 bytes leaves the copy without a record. The probe refuses each of these samples
   * rather than imitate the copy.
   */
  static Optional<List<byte[]>> firstAsCopied(JsonNode stream, NalSyntax syntax) {
    var lengthSize = Extradata.of(stream).flatMap(syntax::lengthSize);
    if (lengthSize.isEmpty()) {
      return Optional.empty();
    }

    return FirstPackets.of(stream)
        .flatMap(packets -> packets.stream().findFirst())
        .flatMap(sample -> unitsAsCopied(sample, lengthSize.get(), syntax));
  }

  private static Optional<List<byte[]>> unitsAsCopied(
      byte[] sample, int lengthSize, NalSyntax syntax) {
    var units = new ArrayList<byte[]>();
    var position = 0;
    while (position < sample.length) {
      if (sample.length - position < lengthSize) {
        return Optional.empty();
      }

      var length = 0;
      for (var index = 0; index < lengthSize; index++) {
        length = (length << 8) | Byte.toUnsignedInt(sample[position + index]);
      }

      position += lengthSize;
      if (length <= 0 || length > sample.length - position) {
        return Optional.empty();
      }

      var region = DecoderConfiguration.lengthPrefixedRegion(sample, position, position + length);
      if (!region.complete() || !region.units().stream().allMatch(syntax::reachesDecoder)) {
        return Optional.empty();
      }

      units.addAll(region.units());
      position += length;
    }

    return Optional.of(units);
  }

  /** The sample's NAL units that reach FFmpeg's decoders; incomplete when none does. */
  private static NalUnits reachingDecoder(NalUnits sample, NalSyntax syntax) {
    var reaching = syntax.reachingDecoder(sample);
    return new NalUnits(reaching.units(), reaching.complete() && !reaching.units().isEmpty());
  }

  /**
   * The sample's NAL units, each without its emulation prevention bytes. FFmpeg's
   * ff_h2645_packet_split reads a length only while at least {@link #FEWEST_SPLIT_BYTES} bytes
   * remain, so the reader drops the 1 to 3 bytes that can end a sample, whatever they hold. A
   * length that it reads of 0, or one that runs past the sample, makes FFmpeg reject the whole
   * sample, so such a sample is incomplete and holds no NAL unit that a decoder sees. A start code
   * inside a length's bytes leaves the sample incomplete.
   */
  private static NalUnits lengthPrefixed(byte[] sample, int lengthSize) {
    var units = new ArrayList<byte[]>();
    var isEveryRegionComplete = true;
    var position = 0;
    while (sample.length - position >= FEWEST_SPLIT_BYTES) {
      var length = 0;
      for (var index = 0; index < lengthSize; index++) {
        length = (length << 8) | Byte.toUnsignedInt(sample[position + index]);
      }

      position += lengthSize;
      if (length <= 0 || length > sample.length - position) {
        return new NalUnits(List.of(), false);
      }

      var region = DecoderConfiguration.lengthPrefixedRegion(sample, position, position + length);
      units.addAll(region.units());
      isEveryRegionComplete &= region.complete();
      position += length;
    }

    return new NalUnits(units, isEveryRegionComplete);
  }
}
