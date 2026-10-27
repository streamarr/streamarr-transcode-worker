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
   * no extradata to tell how the samples delimit their NAL units.
   */
  static Optional<RpuObservation> read(JsonNode stream) {
    var dumps = stream.path(FirstPacketsProbe.FIRST_PACKETS).valueStream().toList();
    var extradata = Extradata.of(stream);
    if (dumps.isEmpty() || extradata.isEmpty()) {
      return Optional.empty();
    }

    var lengthSize = lengthSize(extradata.get());
    var samples = new ArrayList<List<byte[]>>();
    for (var dump : dumps) {
      var bytes = Extradata.dumped(dump.path("data").asString(""));
      if (bytes.isEmpty()) {
        return Optional.empty();
      }

      samples.add(
          lengthSize.isPresent()
              ? lengthPrefixed(bytes.get(), lengthSize.get())
              : DecoderConfiguration.annexB(bytes.get()));
    }

    return Optional.of(observation(samples));
  }

  private static RpuObservation observation(List<List<byte[]>> samples) {
    var rpus = samples.stream().flatMap(List::stream).filter(H264RpuReader::isRpu).toList();
    if (rpus.isEmpty()) {
      return new RpuObservation(
          Presence.PRESENCE_ABSENT, Optional.empty(), Presence.PRESENCE_UNSPECIFIED);
    }

    var startsSample = samples.stream().anyMatch(H264RpuReader::startsWithRpu);
    return new RpuObservation(
        Presence.PRESENCE_PRESENT,
        header(rpus.getFirst()),
        startsSample ? Presence.PRESENCE_PRESENT : Presence.PRESENCE_ABSENT);
  }

  /** Whether an RPU comes ahead of the sample's first slice, or the sample holds no slice. */
  private static boolean startsWithRpu(List<byte[]> sample) {
    for (var unit : sample) {
      if (isRpu(unit)) {
        return true;
      }

      var type = unit[0] & 0x1F;
      if (type >= FIRST_VCL_NAL_UNIT_TYPE && type <= LAST_VCL_NAL_UNIT_TYPE) {
        return false;
      }
    }

    return false;
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
   * the sample ends the sample.
   */
  private static List<byte[]> lengthPrefixed(byte[] sample, int lengthSize) {
    var units = new ArrayList<byte[]>();
    var position = 0;
    while (position + lengthSize <= sample.length) {
      var length = 0;
      for (var index = 0; index < lengthSize; index++) {
        length = (length << 8) | Byte.toUnsignedInt(sample[position + index]);
      }

      position += lengthSize;
      if (length < 0 || length > sample.length - position) {
        break;
      }

      if (length > 0) {
        units.add(
            DecoderConfiguration.withoutEmulationPrevention(sample, position, position + length));
      }

      position += length;
    }

    return units;
  }
}
