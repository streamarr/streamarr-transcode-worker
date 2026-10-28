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

  /** The sample's NAL units that reach FFmpeg's decoders; incomplete when none does. */
  private static NalUnits reachingDecoder(NalUnits sample, NalSyntax syntax) {
    var reaching = syntax.reachingDecoder(sample);
    return new NalUnits(reaching.units(), reaching.complete() && !reaching.units().isEmpty());
  }

  /**
   * The sample's NAL units, each without its emulation prevention bytes. A length that runs past
   * the sample, or bytes too few for a length, end the sample early, and a start code inside a
   * length's bytes leaves the sample incomplete. FFmpeg rejects a whole sample that holds a length
   * of 0, so such a sample is incomplete and holds no NAL unit that a decoder sees. FFmpeg's
   * ff_h2645_packet_split stops when fewer than {@link #FEWEST_SPLIT_BYTES} bytes remain, so the
   * reader skips the NAL unit that such bytes hold.
   */
  private static NalUnits lengthPrefixed(byte[] sample, int lengthSize) {
    var units = new ArrayList<byte[]>();
    var isEveryRegionComplete = true;
    var position = 0;
    while (position + lengthSize <= sample.length) {
      var isSplit = sample.length - position >= FEWEST_SPLIT_BYTES;
      var length = 0;
      for (var index = 0; index < lengthSize; index++) {
        length = (length << 8) | Byte.toUnsignedInt(sample[position + index]);
      }

      position += lengthSize;
      if (length == 0) {
        return new NalUnits(List.of(), false);
      }

      if (length < 0 || length > sample.length - position) {
        return new NalUnits(units, false);
      }

      var region = DecoderConfiguration.lengthPrefixedRegion(sample, position, position + length);
      if (isSplit) {
        units.addAll(region.units());
      }

      isEveryRegionComplete &= region.complete();
      position += length;
    }

    return new NalUnits(units, isEveryRegionComplete && position == sample.length);
  }
}
