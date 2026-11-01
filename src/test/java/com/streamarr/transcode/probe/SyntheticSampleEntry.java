package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.SyntheticMp4.box;
import static com.streamarr.transcode.probe.SyntheticMp4.concat;
import static com.streamarr.transcode.probe.SyntheticMp4.int32;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.experimental.UtilityClass;

/** Sample entries for {@link SyntheticMp4}'s stsd, with only the fields the measurer reads. */
@UtilityClass
class SyntheticSampleEntry {

  static final int HEVC_VPS = 32;
  static final int HEVC_SPS = 33;
  static final int HEVC_PPS = 34;

  static final int HEVC_PREFIX_SEI = 39;

  // High profile level 4.0, as the avcC header and the SPS both name it.
  private static final byte[] AVC_PROFILE = {0x64, 0, 0x28};
  private static final byte[] AVC_SPS = {0x67, 0x64, 0, 0x28, (byte) 0xAC, (byte) 0xD9};
  private static final byte[] AVC_PPS = {0x68, (byte) 0xEE, 0x3C, (byte) 0x80};

  // An x265 hvcC header, Main 10 level 4.1, and the parameter sets whose profile it names.
  private static final byte[] HEVC_PROFILE = HexFormat.of().parseHex("0220000000b000000000007b");
  private static final byte[] HEVC_HEADER_TAIL = HexFormat.of().parseHex("f000fcfdfafa0000");
  private static final Map<Integer, byte[]> HEVC_UNITS =
      Map.of(
          HEVC_VPS,
          HexFormat.of().parseHex("40010c01ffff022000000300b0000003000003007b170240"),
          HEVC_SPS,
          HexFormat.of()
              .parseHex(
                  "420101022000000300b0000003000003007ba003c0801107cad8817b916452ffcb9fc4feb016a020402010"),
          HEVC_PPS,
          HexFormat.of().parseHex("4401c072f05324"),
          // x265's information message: user data unregistered, payload type 5.
          HEVC_PREFIX_SEI,
          HexFormat.of().parseHex("4e0105142ca2de09b51747dbbb55a4fe7fc2fc4e7832363580"));

  /**
   * The builder of an H.264 entry whose avcC holds {@code sequenceParameterSets} SPS and {@code
   * pictureParameterSets} PPS behind NAL lengths of {@code lengthBytes}, and whose header names
   * {@code levelIdc}. Unless the caller sets them, the entry is an avc1 with NAL lengths of 4
   * bytes, one SPS and one PPS, and its header names level 4.0, as its SPS does.
   */
  static AvcBuilder avc() {
    return avcEntry()
        .type("avc1")
        .lengthBytes(4)
        .sequenceParameterSets(1)
        .pictureParameterSets(1)
        .levelIdc(AVC_PROFILE[2]);
  }

  @Builder(builderMethodName = "avcEntry", builderClassName = "AvcBuilder")
  private static byte[] avcEntryBytes(
      String type,
      int lengthBytes,
      int sequenceParameterSets,
      int pictureParameterSets,
      int levelIdc) {
    var record = new ByteArrayOutputStream();
    record.write(1);
    record.writeBytes(new byte[] {AVC_PROFILE[0], AVC_PROFILE[1], (byte) levelIdc});
    record.write(0xFC | (lengthBytes - 1));
    record.write(0xE0 | sequenceParameterSets);
    for (var i = 0; i < sequenceParameterSets; i++) {
      writeUnit(record, AVC_SPS);
    }

    record.write(pictureParameterSets);
    for (var i = 0; i < pictureParameterSets; i++) {
      writeUnit(record, AVC_PPS);
    }

    return visual(type, box("avcC", record.toByteArray()));
  }

  /**
   * The builder of an HEVC entry whose hvcC holds one NAL unit of each of {@code unitTypes} behind
   * NAL lengths of {@code lengthBytes}. Unless the caller sets them, the entry is an hvc1 with NAL
   * lengths of 4 bytes and a VPS, an SPS and a PPS.
   */
  static HevcBuilder hevc() {
    return hevcEntry().type("hvc1").lengthBytes(4).unitTypes(List.of(HEVC_VPS, HEVC_SPS, HEVC_PPS));
  }

  @Builder(builderMethodName = "hevcEntry", builderClassName = "HevcBuilder")
  private static byte[] hevcEntryBytes(String type, int lengthBytes, List<Integer> unitTypes) {
    return hevcWithUnits(type, lengthBytes, unitTypes.stream().map(HEVC_UNITS::get).toList());
  }

  /**
   * An hvc1 entry whose hvcC holds a VPS, an SPS, a PPS and a prefix SEI NAL unit with one message
   * of this payload type and a 1-byte payload.
   */
  static byte[] hevcWithSeiMessage(int payloadType) {
    var sei = new byte[] {0x4E, 0x01, (byte) payloadType, 1, 0, (byte) 0x80};
    return hevcWithUnits(
        "hvc1",
        4,
        List.of(HEVC_UNITS.get(HEVC_VPS), HEVC_UNITS.get(HEVC_SPS), HEVC_UNITS.get(HEVC_PPS), sei));
  }

  private static byte[] hevcWithUnits(String type, int lengthBytes, Collection<byte[]> units) {
    var record = new ByteArrayOutputStream();
    record.write(1);
    record.writeBytes(HEVC_PROFILE);
    record.writeBytes(HEVC_HEADER_TAIL);
    record.write(0x0C | (lengthBytes - 1));
    record.write(units.size());
    for (var unit : units) {
      record.write(0x80 | ((unit[0] >> 1) & 0x3F));
      record.writeBytes(new byte[] {0, 1});
      writeUnit(record, unit);
    }

    return visual(type, box("hvcC", record.toByteArray()));
  }

  private static void writeUnit(ByteArrayOutputStream record, byte[] unit) {
    record.writeBytes(new byte[] {(byte) (unit.length >> 8), (byte) unit.length});
    record.writeBytes(unit);
  }

  /** A video entry of this type with these child boxes, such as none for Annex B video. */
  static byte[] visual(String type, byte[]... children) {
    return box(type, concat(new byte[78], concat(children)));
  }

  /** An AAC-LC AudioSpecificConfig for 48 kHz stereo. */
  static final byte[] AUDIO_SPECIFIC_CONFIG = {0x11, (byte) 0x90};

  /** An mp4a entry whose esds holds this DecoderSpecificInfo. */
  static byte[] mp4aWithEsds(byte[] decoderSpecificInfo) {
    return audio("mp4a", 0, esds(0, decoderSpecificInfo));
  }

  /**
   * A QuickTime version 1 mp4a entry that wraps its esds in a wave box, with an ES_Descriptor whose
   * flags announce a stream dependence, a URL and an OCR stream.
   */
  static byte[] quickTimeMp4aWithEsdsInWave() {
    return audio("mp4a", 1, box("wave", esds(0xE0, AUDIO_SPECIFIC_CONFIG)));
  }

  /**
   * An esds whose ES_Descriptor has these flags and the optional fields they announce, then a
   * DecoderConfigDescriptor for AAC that holds this DecoderSpecificInfo, or none when it is null.
   * Each descriptor length takes the four-byte form that some muxers write.
   */
  static byte[] esds(int flags, byte[] decoderSpecificInfo) {
    var config = new ByteArrayOutputStream();
    config.writeBytes(new byte[] {0x40, 0x15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    if (decoderSpecificInfo != null) {
      config.writeBytes(descriptor(5, decoderSpecificInfo));
    }

    var stream = new ByteArrayOutputStream();
    stream.writeBytes(new byte[] {0, 1, (byte) flags});
    if ((flags & 0x80) != 0) {
      stream.writeBytes(new byte[] {0, 2});
    }

    if ((flags & 0x40) != 0) {
      stream.writeBytes(new byte[] {3, 'u', 'r', 'l'});
    }

    if ((flags & 0x20) != 0) {
      stream.writeBytes(new byte[] {0, 3});
    }

    stream.writeBytes(descriptor(4, config.toByteArray()));
    stream.writeBytes(descriptor(6, new byte[] {2}));
    return box("esds", int32(0), descriptor(3, stream.toByteArray()));
  }

  private static byte[] descriptor(int tag, byte[] payload) {
    var length = payload.length;
    return concat(
        new byte[] {
          (byte) tag,
          (byte) (0x80 | (length >> 21)),
          (byte) (0x80 | ((length >> 14) & 0x7F)),
          (byte) (0x80 | ((length >> 7) & 0x7F)),
          (byte) (length & 0x7F)
        },
        payload);
  }

  /** An audio entry of this type with a sound description version and these child boxes. */
  static byte[] audio(String type, int version, byte[]... children) {
    var fields = new byte[28 + (version == 1 ? 16 : 0) + (version == 2 ? 36 : 0)];
    fields[9] = (byte) version;
    return box(type, concat(fields, concat(children)));
  }
}
