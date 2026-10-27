package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.Presence;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Finds the Dolby Vision RPUs in the first packets of an H.264 stream, as {@link FirstPacketsProbe}
 * adds them. An RPU is a NAL unit of type 28 whose payload starts with rpu_nal_prefix 25. An avcC
 * record means samples of length-prefixed NAL units, and Annex B extradata, as MPEG-TS carries,
 * means start codes.
 */
final class H264RpuReader {

  private static final int AVC_RECORD_VERSION = 1;
  // The avcC record's lengthSizeMinusOne sits in the low 2 bits of its fifth byte.
  private static final int AVC_RECORD_LENGTH_SIZE = 4;
  private static final int RPU_NAL_UNIT_TYPE = 28;
  private static final int RPU_NAL_PREFIX = 25;
  private static final int FIRST_VCL_NAL_UNIT_TYPE = 1;
  private static final int LAST_VCL_NAL_UNIT_TYPE = 5;

  private H264RpuReader() {}

  /**
   * The RPUs that the packets show; empty when the probe read no packet, cannot parse one, or finds
   * no extradata to tell how the samples delimit their NAL units. A sample whose NAL units the
   * reader cannot delimit to its end can hide an RPU, so it rules out no RPU.
   */
  static Optional<RpuObservation> read(JsonNode stream) {
    var dumps = stream.path(FirstPacketsProbe.FIRST_PACKETS).valueStream().toList();
    var extradata = Extradata.of(stream);
    if (dumps.isEmpty() || extradata.isEmpty()) {
      return Optional.empty();
    }

    var lengthSize = lengthSize(extradata.get());
    var samples = new ArrayList<Sample>();
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

    return observation(samples);
  }

  private static Optional<RpuObservation> observation(List<Sample> samples) {
    var rpus =
        samples.stream().flatMap(sample -> sample.units().stream()).filter(H264RpuReader::isRpu);
    var firstRpu = rpus.findFirst();
    var isEverySampleComplete = samples.stream().allMatch(Sample::complete);
    if (firstRpu.isEmpty() && !isEverySampleComplete) {
      return Optional.empty();
    }

    if (firstRpu.isEmpty()) {
      return Optional.of(
          new RpuObservation(
              Presence.PRESENCE_ABSENT, Optional.empty(), Presence.PRESENCE_UNSPECIFIED));
    }

    var layouts = samples.stream().map(H264RpuReader::layout).toList();
    var startsSample = Presence.PRESENCE_ABSENT;
    if (layouts.contains(Layout.UNKNOWN)) {
      startsSample = Presence.PRESENCE_UNSPECIFIED;
    }

    if (layouts.contains(Layout.RPU_FIRST)) {
      startsSample = Presence.PRESENCE_PRESENT;
    }

    return Optional.of(
        new RpuObservation(Presence.PRESENCE_PRESENT, header(firstRpu.get()), startsSample));
  }

  /**
   * Whether an RPU comes ahead of the sample's first slice, or the sample holds no slice. Unknown
   * when the reader could not delimit the sample to its end and found neither ahead of the cut.
   */
  private static Layout layout(Sample sample) {
    for (var unit : sample.units()) {
      if (isRpu(unit)) {
        return Layout.RPU_FIRST;
      }

      var type = unit[0] & 0x1F;
      if (type >= FIRST_VCL_NAL_UNIT_TYPE && type <= LAST_VCL_NAL_UNIT_TYPE) {
        return Layout.PICTURE_FIRST;
      }
    }

    return sample.complete() ? Layout.PICTURE_FIRST : Layout.UNKNOWN;
  }

  private static boolean isRpu(byte[] unit) {
    return unit.length > 1
        && (unit[0] & 0x1F) == RPU_NAL_UNIT_TYPE
        && Byte.toUnsignedInt(unit[1]) == RPU_NAL_PREFIX;
  }

  private static Optional<DolbyVisionRpuHeader> header(byte[] rpu) {
    return DolbyVisionRpuHeaderReader.read(Arrays.copyOfRange(rpu, 1, rpu.length));
  }

  private static Optional<Integer> lengthSize(byte[] extradata) {
    if (extradata.length <= AVC_RECORD_LENGTH_SIZE
        || Byte.toUnsignedInt(extradata[0]) != AVC_RECORD_VERSION) {
      return Optional.empty();
    }

    return Optional.of((extradata[AVC_RECORD_LENGTH_SIZE] & 0x03) + 1);
  }

  /**
   * The sample's NAL units, each without its emulation prevention bytes. A length that runs past
   * the sample, or bytes too few for a length, end the sample early.
   */
  private static Sample lengthPrefixed(byte[] sample, int lengthSize) {
    var units = new ArrayList<byte[]>();
    var position = 0;
    while (position + lengthSize <= sample.length) {
      var length = 0;
      for (var index = 0; index < lengthSize; index++) {
        length = (length << 8) | Byte.toUnsignedInt(sample[position + index]);
      }

      position += lengthSize;
      if (length < 0 || length > sample.length - position) {
        return new Sample(units, false);
      }

      if (length > 0) {
        units.add(
            DecoderConfiguration.withoutEmulationPrevention(sample, position, position + length));
      }

      position += length;
    }

    return new Sample(units, position == sample.length && !units.isEmpty());
  }

  /**
   * A sample without a start code, or with data ahead of its first one, holds bytes that the reader
   * cannot delimit. Zero bytes may lead the first start code.
   */
  private static Sample annexB(byte[] sample) {
    var units = DecoderConfiguration.annexB(sample);
    var leading = 0;
    while (leading < sample.length && sample[leading] == 0) {
      leading++;
    }

    var startsWithStartCode =
        leading >= 2 && leading < sample.length && Byte.toUnsignedInt(sample[leading]) == 1;
    return new Sample(units, startsWithStartCode && !units.isEmpty());
  }

  /** A sample's NAL units, and whether they reach the sample's end. */
  private record Sample(List<byte[]> units, boolean complete) {}

  private enum Layout {
    RPU_FIRST,
    PICTURE_FIRST,
    UNKNOWN
  }
}
