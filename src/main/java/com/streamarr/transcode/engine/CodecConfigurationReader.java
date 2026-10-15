package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.TrackCodec.Audio;
import com.streamarr.transcode.engine.TrackCodec.Video;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Builds each track's RFC 6381 codec string from the sample entry and the decoder configuration
 * record that an initialization segment declares: {@code avcC} (ISO/IEC 14496-15 5.3.2), {@code
 * hvcC} (ISO/IEC 14496-15 8.3.2), {@code av1C} (AV1 ISOBMFF binding 2.3) and {@code esds} (ISO/IEC
 * 14496-1 7.2.6). Hexadecimal digits are uppercase, and every digit is ASCII whatever the default
 * locale.
 */
final class CodecConfigurationReader {

  // A VisualSampleEntry's fields before its child boxes (ISO/IEC 14496-12 12.1.3).
  static final int VISUAL_SAMPLE_ENTRY_BYTES = 78;
  // An AudioSampleEntry's fields before its child boxes (ISO/IEC 14496-12 12.2.3), and what the
  // QuickTime sound description versions 1 and 2 add to them.
  private static final int AUDIO_SAMPLE_ENTRY_BYTES = 28;
  private static final int SOUND_DESCRIPTION_VERSION_1_BYTES = 16;
  private static final int SOUND_DESCRIPTION_VERSION_2_BYTES = 36;

  // The AVC profiles whose avcC carries the chroma format and bit depths after its parameter sets.
  private static final Set<Integer> AVC_PROFILES_WITH_FORMAT = Set.of(100, 110, 122, 144, 244);
  // The hvcC fields before its array count (ISO/IEC 14496-15 8.3.2.1.2), and the NAL unit types of
  // the VPS, SPS and PPS (ITU-T H.265 table 7-1).
  static final int HVCC_FIELDS_BEFORE_ARRAYS = 22;
  private static final Set<Integer> HEVC_PARAMETER_SET_TYPES = Set.of(32, 33, 34);
  private static final int MPEG4_AUDIO = 0x40;
  private static final int ES_DESCRIPTOR = 0x03;
  private static final int DECODER_CONFIG_DESCRIPTOR = 0x04;
  private static final int DECODER_SPECIFIC_INFO = 0x05;
  private static final int ESCAPED_AUDIO_OBJECT_TYPE = 31;

  private CodecConfigurationReader() {}

  /**
   * The codec configuration of every video and audio track, in the order the {@code moov} declares
   * them.
   *
   * @throws FragmentedMp4Exception when a box that the codec string needs is missing or too short
   */
  static List<TrackCodec> tracksOf(InitializationSegment segment) {
    return moovOf(segment).children("trak").stream()
        .map(CodecConfigurationReader::trackCodecOf)
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * The sample entry of the first video track; empty when the {@code moov} declares no video track.
   * This method does not read the sample entry's decoder configuration record. {@link #videoOf}
   * reads the record and throws when it is missing or too short.
   *
   * @throws FragmentedMp4Exception when a box that leads to the sample entry is missing or too
   *     short
   */
  static Optional<BoxView> firstVideoSampleEntryOf(InitializationSegment segment) {
    return moovOf(segment).children("trak").stream()
        .map(trak -> trak.requiredChild("mdia"))
        .filter(mdia -> handlerOf(mdia).equals("vide"))
        .findFirst()
        .map(CodecConfigurationReader::sampleEntryOf);
  }

  /**
   * The codec configuration that a video sample entry and its decoder configuration record declare.
   *
   * @throws FragmentedMp4Exception when the decoder configuration record is missing or too short
   */
  static Video videoOf(BoxView sampleEntry) {
    var children = sampleEntry.after(VISUAL_SAMPLE_ENTRY_BYTES);
    return switch (sampleEntry.type()) {
      case "avc1", "avc3" -> avc(sampleEntry.type(), children.requiredChild("avcC").fields());
      case "hvc1", "hev1" -> hevc(sampleEntry.type(), children.requiredChild("hvcC").fields());
      case "av01" -> av1(children.requiredChild("av1C").fields());
      default -> new Video(sampleEntry.type(), Optional.empty());
    };
  }

  /**
   * Whether the decoder configuration record holds every parameter set that the sample entry
   * promises it holds: {@code avc1} promises an SPS and a PPS, and {@code hvc1} a VPS, an SPS and a
   * PPS. Every other sample entry lets the samples carry its parameter sets, so it promises none.
   *
   * @throws FragmentedMp4Exception when the record is missing or ends before its parameter sets do
   */
  static boolean holdsPromisedParameterSets(BoxView sampleEntry) {
    var children = sampleEntry.after(VISUAL_SAMPLE_ENTRY_BYTES);
    return switch (sampleEntry.type()) {
      case "avc1" -> avcHoldsParameterSets(children.requiredChild("avcC").fields());
      case "hvc1" ->
          hevcArrayTypes(children.requiredChild("hvcC").fields())
              .containsAll(HEVC_PARAMETER_SET_TYPES);
      default -> true;
    };
  }

  private static BoxView moovOf(InitializationSegment segment) {
    return new BoxView("initialization segment", ByteBuffer.wrap(segment.bytes()))
        .requiredChild("moov");
  }

  private static String handlerOf(BoxView mdia) {
    return mdia.requiredChild("hdlr").fields().skip(8).fourcc();
  }

  private static Optional<TrackCodec> trackCodecOf(BoxView trak) {
    var mdia = trak.requiredChild("mdia");
    return switch (handlerOf(mdia)) {
      case "vide" -> Optional.of(videoOf(sampleEntryOf(mdia)));
      case "soun" -> Optional.of(audioOf(sampleEntryOf(mdia)));
      default -> Optional.empty();
    };
  }

  private static BoxView sampleEntryOf(BoxView mdia) {
    var stsd = mdia.requiredChild("minf").requiredChild("stbl").requiredChild("stsd");
    return stsd.after(8).children().stream()
        .findFirst()
        .orElseThrow(() -> FragmentedMp4Exception.malformed("stsd holds no sample entry"));
  }

  private static Video avc(String sampleEntry, BoxFields avcC) {
    avcC.skip(1);
    var profile = avcC.u8();
    var constraints = avcC.u8();
    var level = avcC.u8();
    var codecString =
        String.format(Locale.ROOT, "%s.%02X%02X%02X", sampleEntry, profile, constraints, level);
    if (!AVC_PROFILES_WITH_FORMAT.contains(profile)) {
      return new Video(codecString, Optional.of(PictureFormat.EIGHT_BIT_420));
    }

    avcC.skip(1);
    skipParameterSets(avcC, avcC.u8() & 0x1F);
    skipParameterSets(avcC, avcC.u8());
    if (avcC.remaining() < 3) {
      return new Video(codecString, Optional.empty());
    }

    var chroma = chromaOf(avcC.u8() & 0x03);
    var bitDepth = (avcC.u8() & 0x07) + 8;
    return new Video(codecString, Optional.of(new PictureFormat(bitDepth, chroma)));
  }

  private static void skipParameterSets(BoxFields record, int count) {
    for (var set = 0; set < count; set++) {
      record.skip(record.u16());
    }
  }

  private static boolean avcHoldsParameterSets(BoxFields avcC) {
    avcC.skip(5);
    var sequenceParameterSets = avcC.u8() & 0x1F;
    skipParameterSets(avcC, sequenceParameterSets);
    var pictureParameterSets = avcC.u8();
    return sequenceParameterSets > 0 && pictureParameterSets > 0;
  }

  // The NAL unit types of the hvcC arrays that hold at least one NAL unit.
  private static Set<Integer> hevcArrayTypes(BoxFields hvcC) {
    hvcC.skip(HVCC_FIELDS_BEFORE_ARRAYS);
    var arrays = hvcC.u8();
    var types = new HashSet<Integer>();
    for (var array = 0; array < arrays; array++) {
      var type = hvcC.u8() & 0x3F;
      var nalUnits = hvcC.u16();
      skipParameterSets(hvcC, nalUnits);
      if (nalUnits > 0) {
        types.add(type);
      }
    }

    return types;
  }

  private static Video hevc(String sampleEntry, BoxFields hvcC) {
    hvcC.skip(1);
    var spaceTierAndProfile = hvcC.u8();
    var compatibility = (int) hvcC.u32();
    var constraints = new byte[6];
    for (var index = 0; index < constraints.length; index++) {
      constraints[index] = (byte) hvcC.u8();
    }

    var level = hvcC.u8();
    hvcC.skip(3);
    var chroma = chromaOf(hvcC.u8() & 0x03);
    var bitDepth = (hvcC.u8() & 0x07) + 8;
    var codecString =
        String.format(
            Locale.ROOT,
            "%s.%s%d.%X.%s%d%s",
            sampleEntry,
            profileSpace(spaceTierAndProfile >> 6),
            spaceTierAndProfile & 0x1F,
            Integer.reverse(compatibility),
            tierLetter((spaceTierAndProfile & 0x20) != 0, "L"),
            level,
            constraintSuffix(constraints));
    return new Video(codecString, Optional.of(new PictureFormat(bitDepth, chroma)));
  }

  private static String profileSpace(int space) {
    return switch (space) {
      case 1 -> "A";
      case 2 -> "B";
      case 3 -> "C";
      default -> "";
    };
  }

  // Each constraint byte as hexadecimal, without the trailing zero bytes.
  private static String constraintSuffix(byte[] constraints) {
    var last = constraints.length;
    while (last > 0 && constraints[last - 1] == 0) {
      last--;
    }

    var suffix = new StringBuilder();
    for (var index = 0; index < last; index++) {
      suffix.append(String.format(Locale.ROOT, ".%X", Byte.toUnsignedInt(constraints[index])));
    }

    return suffix.toString();
  }

  private static Video av1(BoxFields av1C) {
    av1C.skip(1);
    var profileAndLevel = av1C.u8();
    var flags = av1C.u8();
    var profile = profileAndLevel >> 5;
    var highBitDepth = (flags & 0x40) != 0;
    var twelveBit = (flags & 0x20) != 0;
    var bitDepth = 8;
    if (highBitDepth) {
      bitDepth = 10;
    }

    if (highBitDepth && profile == 2 && twelveBit) {
      bitDepth = 12;
    }

    var codecString =
        String.format(
            Locale.ROOT,
            "av01.%d.%02d%s.%02d",
            profile,
            profileAndLevel & 0x1F,
            tierLetter((flags & 0x80) != 0, "M"),
            bitDepth);
    return new Video(codecString, Optional.of(new PictureFormat(bitDepth, av1ChromaOf(flags))));
  }

  private static ChromaSubsampling av1ChromaOf(int flags) {
    if ((flags & 0x10) != 0) {
      return ChromaSubsampling.MONOCHROME;
    }

    var horizontal = (flags & 0x08) != 0;
    var vertical = (flags & 0x04) != 0;
    if (horizontal && vertical) {
      return ChromaSubsampling.CHROMA_420;
    }

    if (horizontal) {
      return ChromaSubsampling.CHROMA_422;
    }

    return ChromaSubsampling.CHROMA_444;
  }

  // HEVC writes its Main tier as L, and AV1 as M; both write the High tier as H.
  private static String tierLetter(boolean highTier, String mainTierLetter) {
    if (highTier) {
      return "H";
    }

    return mainTierLetter;
  }

  private static ChromaSubsampling chromaOf(int chromaFormat) {
    return switch (chromaFormat) {
      case 0 -> ChromaSubsampling.MONOCHROME;
      case 1 -> ChromaSubsampling.CHROMA_420;
      case 2 -> ChromaSubsampling.CHROMA_422;
      default -> ChromaSubsampling.CHROMA_444;
    };
  }

  private static Audio audioOf(BoxView entry) {
    if (!entry.type().equals("mp4a")) {
      return new Audio(entry.type());
    }

    var version = entry.fields().skip(8).u16();
    var children = entry.after(AUDIO_SAMPLE_ENTRY_BYTES + soundDescriptionExtraBytes(version));
    return new Audio(mp4aCodecString(children.requiredChild("esds").fields().skip(4)));
  }

  private static int soundDescriptionExtraBytes(int version) {
    return switch (version) {
      case 1 -> SOUND_DESCRIPTION_VERSION_1_BYTES;
      case 2 -> SOUND_DESCRIPTION_VERSION_2_BYTES;
      default -> 0;
    };
  }

  private static String mp4aCodecString(BoxFields esds) {
    requireDescriptor(esds, ES_DESCRIPTOR);
    esds.skip(2);
    var flags = esds.u8();
    esds.skipIf((flags & 0x80) != 0, 2);
    if ((flags & 0x40) != 0) {
      esds.skip(esds.u8());
    }

    esds.skipIf((flags & 0x20) != 0, 2);
    requireDescriptor(esds, DECODER_CONFIG_DESCRIPTOR);
    var objectType = esds.u8();
    esds.skip(12);
    var codecString = "mp4a." + HexFormat.of().withUpperCase().toHexDigits((byte) objectType);
    if (objectType != MPEG4_AUDIO || esds.remaining() == 0) {
      return codecString;
    }

    requireDescriptor(esds, DECODER_SPECIFIC_INFO);
    return codecString + "." + audioObjectTypeOf(esds);
  }

  // The audioObjectType that starts the AudioSpecificConfig (ISO/IEC 14496-3 1.6.2.1).
  private static int audioObjectTypeOf(BoxFields config) {
    var first = config.u8();
    var objectType = first >> 3;
    if (objectType != ESCAPED_AUDIO_OBJECT_TYPE) {
      return objectType;
    }

    return 32 + (((first & 0x07) << 3) | (config.u8() >> 5));
  }

  // Reads a descriptor's tag and its expandable size, leaving the fields at its body.
  private static void requireDescriptor(BoxFields esds, int tag) {
    var actual = esds.u8();
    if (actual != tag) {
      throw FragmentedMp4Exception.malformed(
          "esds holds descriptor " + actual + " where descriptor " + tag + " belongs");
    }

    var sizeByte = esds.u8();
    for (var count = 1; (sizeByte & 0x80) != 0 && count < 4; count++) {
      sizeByte = esds.u8();
    }
  }
}
