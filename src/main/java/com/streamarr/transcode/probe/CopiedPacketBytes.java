package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import tools.jackson.databind.JsonNode;

/**
 * Whether a copy of an H.264 or HEVC stream without the Annex B bitstream filter delivers every
 * packet with its bytes unchanged, under the codec string that the probe reads from the record's
 * parameter sets (ADRs 0054 and 0037). The mp4 muxer then writes the source's decoder configuration
 * record and samples as they are. That holds only when:
 *
 * <ul>
 *   <li>the record is a whole avcC or hvcC that holds an SPS and a PPS, and for HEVC a VPS, so the
 *       copy's record holds the parameter sets without taking them from the samples;
 *   <li>the record's NAL unit lengths take 4 bytes;
 *   <li>the record header names the profile, tier and level that {@link DecoderConfiguration} reads
 *       from the record's parameter sets, because the muxer keeps the header;
 *   <li>every SEI message in an hvcC is user data unregistered, because the worker removes the SEI
 *       arrays from the copy's record and only the filter would repeat their messages in the
 *       samples;
 *   <li>a source with a sample entry names {@code avc1} or {@code dva1} for H.264 and {@code hvc1}
 *       or {@code dvh1} for HEVC, the entries whose records hold the parameter sets;
 *   <li>a source with a sample entry holds no other, as {@link Mp4SampleEntries} reads, because
 *       ffprobe describes only the first and only the filter carries another entry's parameter sets
 *       into the samples;
 *   <li>a source without sample entries is Matroska, whose track keeps one record. An FLV stream
 *       can carry a new record in any packet, and only the filter delivers its parameter sets.
 * </ul>
 */
final class CopiedPacketBytes {

  private static final Set<String> AVC_RECORD_SAMPLE_ENTRIES = Set.of("avc1", "dva1");
  private static final Set<String> HEVC_RECORD_SAMPLE_ENTRIES = Set.of("hvc1", "dvh1");
  private static final int RECORD_VERSION = 1;
  // The avcC record's count of SPS NAL units follows its 5 header bytes, and the hvcC record's
  // count
  // of NAL unit arrays follows its 22.
  private static final int AVC_RECORD_SPS_COUNT = 5;
  private static final int HVCC_ARRAY_COUNT = 22;
  private static final int NAL_LENGTH_BYTES = 4;
  private static final int HEVC_VIDEO_PARAMETER_SET = 32;
  private static final int HEVC_SEQUENCE_PARAMETER_SET = 33;
  private static final int HEVC_PICTURE_PARAMETER_SET = 34;
  private static final int USER_DATA_UNREGISTERED = 5;
  private static final int TRAILING_BITS = 0x80;

  private static final String NO_CODEC_TAG = "0x0000";

  private CopiedPacketBytes() {}

  /**
   * Whether a copy of the H.264 or HEVC stream keeps every packet's bytes; empty for any other
   * codec. {@code formatName} is the container's format_name, as ffprobe reports it. A stream
   * without sample entries counts only in Matroska.
   */
  static Optional<Boolean> of(@NonNull JsonNode stream, @NonNull String formatName) {
    var kept = recordKeepsPacketBytes(stream);
    if (kept.isEmpty() || sampleEntry(stream).isPresent()) {
      return kept;
    }

    return kept.map(isKept -> isKept && ProbedSource.MATROSKA_FORMAT.equals(formatName));
  }

  /**
   * Whether the stream's record and sample entries keep every packet's bytes in a copy, whatever
   * the container; empty for a codec other than H.264 and HEVC. A stream without extradata has no
   * record. A track whose number of sample entries the probe could not read counts as one with
   * several.
   */
  static Optional<Boolean> recordKeepsPacketBytes(JsonNode stream) {
    var family = ProbedCodecFamily.of(stream);
    if (family != ProbedCodecFamily.H264 && family != ProbedCodecFamily.HEVC) {
      return Optional.empty();
    }

    var sampleEntry = sampleEntry(stream);
    if (sampleEntry.isPresent()
        && stream.path(Mp4SampleEntries.SAMPLE_ENTRY_COUNT_FIELD).asLong(0) != 1) {
      return Optional.of(false);
    }

    return Optional.of(
        Extradata.of(stream).filter(record -> areKept(family, sampleEntry, record)).isPresent());
  }

  /**
   * The type of the stream's first sample entry. ffprobe names a codec tag of 0 for a container
   * without sample entries, such as Matroska or FLV, and otherwise names the tag, which is the type
   * of the first sample entry of an MP4 or MOV track.
   */
  private static Optional<String> sampleEntry(JsonNode stream) {
    return text(stream, "codec_tag")
        .filter(tag -> !NO_CODEC_TAG.equals(tag))
        .flatMap(_ -> text(stream, "codec_tag_string"));
  }

  /**
   * Whether a copy without the filter keeps every packet's bytes. {@code sampleEntry} is the
   * source's sample entry type, empty for a container without sample entries, and {@code record} is
   * the stream's extradata.
   */
  static boolean areKept(
      @NonNull ProbedCodecFamily family,
      @NonNull Optional<String> sampleEntry,
      byte @NonNull [] record) {
    return switch (family) {
      case H264 ->
          sampleEntry.map(AVC_RECORD_SAMPLE_ENTRIES::contains).orElse(true)
              && isKeptAvcRecord(record);
      case HEVC ->
          sampleEntry.map(HEVC_RECORD_SAMPLE_ENTRIES::contains).orElse(true)
              && isKeptHevcRecord(record);
      case AV1, OTHER -> false;
    };
  }

  private static boolean isKeptAvcRecord(byte[] record) {
    if (!isRecordWithFourByteLengths(record, NalSyntax.H264)
        || !DecoderConfiguration.isWholeAvcRecord(record)
        || !avcRecordUnits(record).stream().allMatch(NalSyntax.H264::reachesDecoder)) {
      return false;
    }

    return DecoderConfiguration.avcCopyProfile(record)
        .filter(profile -> headerEquals(record, profile, CodecStrings.AVC_PROFILE_BYTES))
        .isPresent();
  }

  private static boolean isKeptHevcRecord(byte[] record) {
    if (!isRecordWithFourByteLengths(record, NalSyntax.HEVC)) {
      return false;
    }

    // A record whose NAL unit FFmpeg's splitter drops is no whole record, and FFmpeg's hvcC writer
    // sorts NAL units by the type that their array names.
    var units = DecoderConfiguration.hevcRecordUnits(record);
    if (!units.complete()
        || !units.units().stream().allMatch(NalSyntax.HEVC::reachesDecoder)
        || !arraysNameTheirUnitsTypes(record)
        || !holdsHevcParameterSets(units.units())) {
      return false;
    }

    return units.units().stream()
            .filter(NalSyntax.HEVC::isSei)
            .allMatch(CopiedPacketBytes::holdsOnlyUserDataUnregistered)
        && DecoderConfiguration.hevcProfile(record)
            .filter(profile -> headerEquals(record, profile, CodecStrings.HEVC_PROFILE_BYTES))
            .isPresent();
  }

  /**
   * The NAL units that a whole avcC record counts in its SPS and PPS lists, each as the record
   * holds it. FFmpeg reads a PPS count past the record's end as 0.
   */
  private static List<byte[]> avcRecordUnits(byte[] avcC) {
    var units = new ArrayList<byte[]>();
    var buffer = ByteBuffer.wrap(avcC).position(AVC_RECORD_SPS_COUNT);
    addLengthPrefixedUnits(buffer, buffer.get() & 0x1F, units);
    addLengthPrefixedUnits(
        buffer, buffer.hasRemaining() ? Byte.toUnsignedInt(buffer.get()) : 0, units);
    return units;
  }

  /** Whether every NAL unit of the whole hvcC record has the NAL_unit_type that its array names. */
  private static boolean arraysNameTheirUnitsTypes(byte[] hvcC) {
    var buffer = ByteBuffer.wrap(hvcC).position(HVCC_ARRAY_COUNT + 1);
    for (var array = 0; array < Byte.toUnsignedInt(hvcC[HVCC_ARRAY_COUNT]); array++) {
      var type = buffer.get() & 0x3F;
      var units = new ArrayList<byte[]>();
      addLengthPrefixedUnits(buffer, Short.toUnsignedInt(buffer.getShort()), units);
      if (!units.stream().allMatch(unit -> NalSyntax.HEVC.type(unit) == type)) {
        return false;
      }
    }

    return true;
  }

  // The caller has checked that every unit ends within the record.
  private static void addLengthPrefixedUnits(ByteBuffer buffer, int count, List<byte[]> units) {
    for (var index = 0; index < count; index++) {
      var unit = new byte[Short.toUnsignedInt(buffer.getShort())];
      buffer.get(unit);
      units.add(unit);
    }
  }

  private static boolean isRecordWithFourByteLengths(byte[] record, NalSyntax syntax) {
    return Byte.toUnsignedInt(record[0]) == RECORD_VERSION
        && syntax.lengthSize(record).filter(size -> size == NAL_LENGTH_BYTES).isPresent();
  }

  private static boolean holdsHevcParameterSets(List<byte[]> units) {
    var types = units.stream().map(NalSyntax.HEVC::type).toList();
    return types.contains(HEVC_VIDEO_PARAMETER_SET)
        && types.contains(HEVC_SEQUENCE_PARAMETER_SET)
        && types.contains(HEVC_PICTURE_PARAMETER_SET);
  }

  // The profile fields follow the record's configurationVersion.
  private static boolean headerEquals(byte[] record, byte[] profile, int profileBytes) {
    return record.length > profileBytes
        && Arrays.equals(record, 1, 1 + profileBytes, profile, 0, profileBytes);
  }

  /**
   * Whether every message of the SEI NAL unit is user data unregistered, and the messages fill the
   * unit up to its RBSP trailing bits.
   */
  private static boolean holdsOnlyUserDataUnregistered(byte[] unit) {
    var end = unit.length;
    while (end > 0 && unit[end - 1] == 0) {
      end--;
    }

    var start = NalSyntax.HEVC.payloadOffset();
    if (end <= start || Byte.toUnsignedInt(unit[end - 1]) != TRAILING_BITS) {
      return false;
    }

    var messages = ByteBuffer.wrap(unit, start, end - 1 - start);
    while (messages.hasRemaining()) {
      var payloadType = SeiMessages.varyingLengthValue(messages);
      var payloadSize = SeiMessages.varyingLengthValue(messages);
      if (payloadType.isEmpty()
          || payloadType.getAsLong() != USER_DATA_UNREGISTERED
          || payloadSize.isEmpty()
          || payloadSize.getAsLong() > messages.remaining()) {
        return false;
      }

      messages.position(messages.position() + (int) payloadSize.getAsLong());
    }

    return true;
  }
}
