package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Rewrites the initialization segment that FFmpeg writes into the one the producer delivers. It
 * removes the SEI messages from every {@code hvc1} decoder configuration record, then writes the
 * static HDR metadata that a copy plans into that record as one prefix SEI array, strips the
 * metadata OBUs that a copy strips from its samples from the {@code av1C} record, and resizes each
 * box that holds a record. For a copy that plans a {@code dvh1} output, the {@code hvc1} entry
 * becomes {@code dvh1}. For a copy that normalizes its Dolby Vision record, the video sample entry
 * holds the record of the planned output in place of any record that FFmpeg wrote. When a copy's
 * source keeps its parameter sets only in its samples, FFmpeg builds the record from the first
 * sample and copies that sample's SEI messages into it. Attempts that start at different keyframes
 * would then write different records, which ADR 0037 forbids, so the record holds only what the job
 * names. The samples keep their SEI messages. An AV1 encode's {@code av1C} record holds the
 * sequence header of the first video sample in place of the one that FFmpeg wrote: SVT-AV1 writes
 * that one before it chooses its coding tools, and puts another in the video stream itself. Every
 * sequence header of a coded video sequence must match (AV1 7.5), and a decoder can initialize from
 * {@code av1C}. The encoder settings alone decide the in-band header, so every attempt writes the
 * same record.
 */
final class InitializationSegmentRewrite {

  // The NAL unit types of prefix and suffix SEI messages (ITU-T H.265 table 7-1).
  private static final Set<Integer> SEI_TYPES = Set.of(39, 40);
  // The stsd fields before its sample entries: version, flags and entry count.
  private static final int STSD_FIELDS = 8;

  private static final int PREFIX_SEI = 39;
  // The av1C fields before its configOBUs.
  private static final int AV1C_FIELDS = 4;
  private static final int OBU_SEQUENCE_HEADER = 1;
  // A box header's type follows its 32-bit size, in the compact and the 64-bit form alike.
  private static final int TYPE_OFFSET = 4;

  // The SEI NAL units that every hvc1 record of the segment holds after the rewrite.
  private final List<byte[]> seiUnits;
  // The type that the video's hvc1 sample entry takes; empty when it keeps hvc1.
  private final Optional<String> renamedHevcEntry;
  // The Dolby Vision record box that replaces any record of the video's sample entry.
  private final Optional<byte[]> normalizedRecord;
  // The rewrite that strips av1C's configOBUs as the copy strips its samples; empty for a decision
  // that lists no rewrite of samples.
  private final Optional<Av1SampleRewrite> configurationObuRewrite;
  // The sequence header that av1C takes from the first video sample; empty for any video but an AV1
  // encode.
  private final Optional<Av1SequenceHeader> inBandSequenceHeader;
  private final boolean encodesHevcMain;

  private InitializationSegmentRewrite(
      VideoDecision decision, Optional<Av1SequenceHeader> inBandSequenceHeader) {
    this.seiUnits = plannedSeiUnitsOf(decision);
    var dolbyVision = plannedDolbyVisionOf(decision);
    this.renamedHevcEntry =
        dolbyVision
            .filter(output -> output.sampleEntry() == PlannedCopyOutput.SampleEntry.DVH1)
            .map(output -> output.sampleEntry().type());
    this.normalizedRecord =
        dolbyVision
            .filter(_ -> decision.lists(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
            .map(output -> DolbyVisionRecord.normalizedFor(output).box());
    this.configurationObuRewrite = configurationObuRewriteOf(decision);
    this.inBandSequenceHeader = inBandSequenceHeader;
    this.encodesHevcMain =
        decision instanceof VideoDecision.Encode encode
            && encode.codecProfile() == VideoCodecProfile.HEVC_MAIN;
  }

  private static Optional<Av1SampleRewrite> configurationObuRewriteOf(VideoDecision decision) {
    if (!(decision instanceof VideoDecision.Copy copy)) {
      return Optional.empty();
    }

    var sampleRewrites =
        copy.bitstreamRewrites().stream()
            .filter(BitstreamRewrite::rewritesSamples)
            .collect(Collectors.toUnmodifiableSet());
    if (sampleRewrites.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(new Av1SampleRewrite(sampleRewrites));
  }

  /**
   * @throws FragmentedMp4Exception with {@link FragmentedMp4Exception.Reason#MALFORMED_BOX} when a
   *     box on the way to a record declares a size that its parent cannot hold, or an av1C record
   *     whose configOBUs the copy strips holds an OBU that cannot be read; with {@link
   *     FragmentedMp4Exception.Reason#OUTPUT_MISMATCH} when a record ends before its arrays do
   */
  static InitializationSegment rewrite(VideoDecision decision, InitializationSegment segment) {
    return new InitializationSegmentRewrite(decision, Optional.empty()).rebuilt(segment);
  }

  /**
   * Whether the rewrite needs the first video sample, which an AV1 encode's {@code av1C} takes its
   * sequence header from.
   *
   * @throws FragmentedMp4Exception with {@link FragmentedMp4Exception.Reason#MALFORMED_BOX} when a
   *     box on the way to the video sample entry is missing or too short
   */
  static boolean awaitsFirstVideoSample(VideoDecision decision, InitializationSegment segment) {
    return decision instanceof VideoDecision.Encode
        && CodecConfigurationReader.firstVideoSampleEntryOf(segment)
            .filter(sampleEntry -> sampleEntry.type().equals("av01"))
            .isPresent();
  }

  /**
   * The rewrite of an AV1 encode's initialization segment, whose {@code av1C} holds the first video
   * sample's sequence header ahead of its other configOBUs.
   *
   * @throws FragmentedMp4Exception with {@link FragmentedMp4Exception.Reason#OUTPUT_MISMATCH} when
   *     the sample holds no sequence header, the {@code av1C} ends before its configOBUs, or its
   *     profile, level, tier, bit depth or chroma fields differ from the sequence header's; with
   *     {@link FragmentedMp4Exception.Reason#MALFORMED_BOX} when the sample's OBUs before the
   *     sequence header, the sequence header itself, or the {@code av1C}'s configOBUs cannot be
   *     read
   */
  static InitializationSegment rewrite(
      VideoDecision decision, InitializationSegment segment, ByteBuffer firstVideoSample) {
    var inBandSequenceHeader = Av1SequenceHeader.firstIn(firstVideoSample);
    return new InitializationSegmentRewrite(decision, Optional.of(inBandSequenceHeader))
        .rebuilt(segment);
  }

  private InitializationSegment rebuilt(InitializationSegment segment) {
    return new InitializationSegment(
        rebuiltChildren("initialization segment", ByteBuffer.wrap(segment.bytes()), Set.of()));
  }

  private static Optional<PlannedCopyOutput.DolbyVisionOutput> plannedDolbyVisionOf(
      VideoDecision decision) {
    if (!(decision instanceof VideoDecision.Copy copy)) {
      return Optional.empty();
    }

    return copy.plannedOutput().flatMap(PlannedCopyOutput::dolbyVision);
  }

  /**
   * The static HDR metadata SEI NAL units that the decision plans for an hvc1 record, in order: a
   * copy's planned values, or a PQ encode's static HDR metadata, which Apple's HLS Authoring
   * Specification item 1.8 asks for in the record.
   */
  static List<byte[]> plannedSeiUnitsOf(VideoDecision decision) {
    return switch (decision) {
      case VideoDecision.Copy copy ->
          copy.plannedOutput().map(HevcStaticMetadataSei::nalUnitsOf).orElse(List.of());
      case VideoDecision.Encode encode ->
          encode
              .staticHdrMetadata()
              .map(
                  metadata ->
                      HevcStaticMetadataSei.nalUnitsOf(
                          Optional.of(metadata.masteringDisplay()),
                          Optional.of(metadata.contentLightLevel())))
              .orElse(List.of());
    };
  }

  // Every child box, rebuilt, apart from those of the dropped types.
  private byte[] rebuiltChildren(String parent, ByteBuffer content, Set<String> droppedTypes) {
    var rebuilt = new ByteArrayOutputStream();
    while (content.hasRemaining()) {
      var start = content.position();
      var header = BoxHeader.read(new BoxFields(parent, content));
      var bodyBytes = header.size() - header.length();
      if (bodyBytes < 0 || bodyBytes > content.remaining()) {
        throw FragmentedMp4Exception.malformed(
            header.type() + " in " + parent + " declares " + header.size() + " bytes");
      }

      var headerBytes = bytesOf(content.slice(start, header.length()));
      var body = content.slice(content.position(), (int) bodyBytes);
      content.position(content.position() + (int) bodyBytes);
      if (!droppedTypes.contains(header.type())) {
        rebuilt.writeBytes(
            resized(renamed(header.type(), headerBytes), rebuiltBody(header.type(), body)));
      }
    }

    return rebuilt.toByteArray();
  }

  private byte[] rebuiltBody(String type, ByteBuffer body) {
    return switch (type) {
      case "moov", "trak", "mdia", "minf", "stbl" -> rebuiltChildren(type, body, Set.of());
      case "stsd" -> rebuiltAfter(type, body, STSD_FIELDS);
      case "hvc1", "av01" -> rebuiltVideoSampleEntry(type, body);
      case "hvcC" -> readable(() -> rewrittenHevcRecord(body));
      case "av1C" -> withInBandSequenceHeader(withoutStrippedConfigurationObus(body));
      default -> bytesOf(body);
    };
  }

  private byte[] rebuiltAfter(String type, ByteBuffer body, int fieldBytes) {
    var children =
        rebuiltChildren(type, new BoxView(type, body).after(fieldBytes).body(), Set.of());
    return concat(bytesOf(body.slice(0, fieldBytes)), children);
  }

  // A normalized record replaces every record that FFmpeg wrote, after the entry's other boxes.
  private byte[] rebuiltVideoSampleEntry(String type, ByteBuffer body) {
    var fieldBytes = CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES;
    var droppedTypes = normalizedRecord.map(_ -> DolbyVisionRecord.BOX_TYPES).orElse(Set.of());
    var children =
        rebuiltChildren(type, new BoxView(type, body).after(fieldBytes).body(), droppedTypes);
    return concat(
        bytesOf(body.slice(0, fieldBytes)), children, normalizedRecord.orElse(new byte[0]));
  }

  // The header with the type that the box takes: a planned dvh1 output renames the hvc1 entry.
  private byte[] renamed(String type, byte[] header) {
    if (!type.equals("hvc1") || renamedHevcEntry.isEmpty()) {
      return header;
    }

    var renamed = header.clone();
    var typeBytes = renamedHevcEntry.get().getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(typeBytes, 0, renamed, TYPE_OFFSET, typeBytes.length);
    return renamed;
  }

  private byte[] withPlannedSeiArray(ByteBuffer hvcC) {
    var reading = hvcC.duplicate();
    var fields = new BoxFields("hvcC", reading);
    fields.skip(CodecConfigurationReader.HVCC_FIELDS_BEFORE_ARRAYS);
    var arrays = fields.u8();
    var kept = new ByteArrayOutputStream();
    var keptArrays = 0;
    for (var array = 0; array < arrays; array++) {
      var arrayStart = reading.position();
      var type = fields.u8() & 0x3F;
      var nalUnits = fields.u16();
      for (var nalUnit = 0; nalUnit < nalUnits; nalUnit++) {
        fields.skip(fields.u16());
      }

      if (!SEI_TYPES.contains(type)) {
        kept.writeBytes(bytesOf(hvcC.slice(arrayStart, reading.position() - arrayStart)));
        keptArrays++;
      }
    }

    if (!seiUnits.isEmpty()) {
      kept.writeBytes(plannedSeiArray());
      keptArrays++;
    }

    return concat(
        bytesOf(hvcC.slice(0, CodecConfigurationReader.HVCC_FIELDS_BEFORE_ARRAYS)),
        new byte[] {(byte) keptArrays},
        kept.toByteArray(),
        bytesOf(reading));
  }

  private byte[] rewrittenHevcRecord(ByteBuffer hvcC) {
    var record = withPlannedSeiArray(hvcC);
    return encodesHevcMain ? HevcMainCompatibility.normalize(record) : record;
  }

  // FFmpeg copies the source's metadata OBUs into av1C's configOBUs (AV1 ISOBMFF binding 2.3.3),
  // so the copy strips them there as it strips them from its samples. A record too short for its
  // fields stays as it is, and the output check refuses it.
  private byte[] withoutStrippedConfigurationObus(ByteBuffer av1C) {
    if (configurationObuRewrite.isEmpty() || av1C.remaining() < AV1C_FIELDS) {
      return bytesOf(av1C);
    }

    var configurationObus = av1C.slice(AV1C_FIELDS, av1C.remaining() - AV1C_FIELDS);
    return concat(
        bytesOf(av1C.slice(0, AV1C_FIELDS)),
        bytesOf(configurationObuRewrite.get().rewrite(configurationObus)));
  }

  // The in-band sequence header replaces any sequence header OBU among the configOBUs, ahead of
  // their other OBUs, once av1C's profile, level, tier, bit depth and chroma fields match it, which
  // keeps the codec string that the record declares.
  private byte[] withInBandSequenceHeader(byte[] av1C) {
    if (inBandSequenceHeader.isEmpty()) {
      return av1C;
    }

    var header = inBandSequenceHeader.get();
    if (av1C.length < AV1C_FIELDS) {
      throw new FragmentedMp4Exception(
          Reason.OUTPUT_MISMATCH, "the AV1 encode's av1C ends before its configOBUs");
    }

    var declared = HexFormat.of().formatHex(av1C, 1, 3);
    var inBand =
        HexFormat.of()
            .formatHex(
                new byte[] {(byte) header.profileAndLevel(), (byte) header.tierDepthAndChroma()});
    if (!declared.equals(inBand)) {
      throw new FragmentedMp4Exception(
          Reason.OUTPUT_MISMATCH,
          "the AV1 encode's av1C declares the codec fields "
              + declared
              + " where its in-band sequence header declares "
              + inBand);
    }

    var configurationObus = ByteBuffer.wrap(av1C, AV1C_FIELDS, av1C.length - AV1C_FIELDS).slice();
    return concat(
        Arrays.copyOf(av1C, AV1C_FIELDS), header.obu(), withoutSequenceHeaders(configurationObus));
  }

  private static byte[] withoutSequenceHeaders(ByteBuffer configurationObus) {
    var kept = new ByteArrayOutputStream();
    var position = 0;
    while (position < configurationObus.limit()) {
      var obu = Av1Obu.at(configurationObus, position);
      if (obu.type() != OBU_SEQUENCE_HEADER) {
        kept.writeBytes(bytesOf(configurationObus.slice(obu.start(), obu.end() - obu.start())));
      }

      position = obu.end();
    }

    return kept.toByteArray();
  }

  // array_completeness stays 0: the samples keep their own SEI messages (ISO/IEC 14496-15
  // 8.3.2.1.3).
  private byte[] plannedSeiArray() {
    var array = ByteBuffer.allocate(3 + seiUnits.stream().mapToInt(unit -> 2 + unit.length).sum());
    array.put((byte) PREFIX_SEI).putShort((short) seiUnits.size());
    seiUnits.forEach(unit -> array.putShort((short) unit.length).put(unit));
    return array.array();
  }

  // A record that cannot be read is an output mismatch, as the output check reports it.
  private static byte[] readable(Supplier<byte[]> reading) {
    try {
      return reading.get();
    } catch (FragmentedMp4Exception e) {
      throw new FragmentedMp4Exception(
          Reason.OUTPUT_MISMATCH,
          "the video codec configuration cannot be read: " + e.getMessage());
    }
  }

  // The box's own header, with its 32-bit or 64-bit size changed to fit the body.
  private static byte[] resized(byte[] header, byte[] body) {
    var box = ByteBuffer.allocate(header.length + body.length).put(header).put(body);
    if (header.length == BoxHeader.LARGE_LENGTH) {
      return box.putLong(BoxHeader.COMPACT_LENGTH, box.capacity()).array();
    }

    return box.putInt(0, box.capacity()).array();
  }

  private static byte[] bytesOf(ByteBuffer buffer) {
    var bytes = new byte[buffer.remaining()];
    buffer.duplicate().get(bytes);
    return bytes;
  }

  private static byte[] concat(byte[]... parts) {
    var joined = new ByteArrayOutputStream();
    for (var part : parts) {
      joined.writeBytes(part);
    }

    return joined.toByteArray();
  }
}
