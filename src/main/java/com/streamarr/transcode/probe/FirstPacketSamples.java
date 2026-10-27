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

  private FirstPacketSamples() {}

  /**
   * The samples of the first packets; empty when the probe read no packet, cannot parse one, or
   * finds no extradata to tell how the samples delimit their NAL units.
   */
  static Optional<List<NalUnits>> read(JsonNode stream, NalSyntax syntax) {
    var dumps = stream.path(FirstPacketsProbe.FIRST_PACKETS).valueStream().toList();
    var extradata = Extradata.of(stream);
    if (dumps.isEmpty() || extradata.isEmpty()) {
      return Optional.empty();
    }

    var lengthSize = syntax.lengthSize(extradata.get());
    var samples = new ArrayList<NalUnits>();
    for (var dump : dumps) {
      var bytes = Extradata.dumped(dump.path("data").asString(""));
      if (bytes.isEmpty()) {
        return Optional.empty();
      }

      samples.add(
          lengthSize.isPresent()
              ? lengthPrefixed(bytes.get(), lengthSize.get())
              : annexB(bytes.get()));
    }

    return Optional.of(samples);
  }

  /**
   * The sample's NAL units, each without its emulation prevention bytes. A length that runs past
   * the sample, or bytes too few for a length, end the sample early.
   */
  private static NalUnits lengthPrefixed(byte[] sample, int lengthSize) {
    var units = new ArrayList<byte[]>();
    var position = 0;
    while (position + lengthSize <= sample.length) {
      var length = 0;
      for (var index = 0; index < lengthSize; index++) {
        length = (length << 8) | Byte.toUnsignedInt(sample[position + index]);
      }

      position += lengthSize;
      if (length < 0 || length > sample.length - position) {
        return new NalUnits(units, false);
      }

      if (length > 0) {
        units.add(
            DecoderConfiguration.withoutEmulationPrevention(sample, position, position + length));
      }

      position += length;
    }

    return new NalUnits(units, position == sample.length && !units.isEmpty());
  }

  /**
   * A sample without a start code, or with data ahead of its first one, holds bytes that the reader
   * cannot delimit. Zero bytes may lead the first start code.
   */
  private static NalUnits annexB(byte[] sample) {
    var units = DecoderConfiguration.annexB(sample);
    var leading = 0;
    while (leading < sample.length && sample[leading] == 0) {
      leading++;
    }

    var startsWithStartCode =
        leading >= 2 && leading < sample.length && Byte.toUnsignedInt(sample[leading]) == 1;
    return new NalUnits(units, startsWithStartCode && !units.isEmpty());
  }
}
