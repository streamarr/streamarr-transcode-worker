package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.SyntheticMp4.box;
import static com.streamarr.transcode.probe.SyntheticMp4.concat;
import static com.streamarr.transcode.probe.SyntheticMp4.int32;

import java.io.ByteArrayOutputStream;
import java.util.List;
import lombok.experimental.UtilityClass;

/** Sample entries for {@link SyntheticMp4}'s stsd, with only the fields the measurer reads. */
@UtilityClass
class SyntheticSampleEntry {

  static final int HEVC_VPS = 32;
  static final int HEVC_SPS = 33;
  static final int HEVC_PPS = 34;

  /** An H.264 entry whose avcC holds these parameter sets behind NAL lengths of these bytes. */
  static byte[] avc(String type, int lengthBytes, int sequenceParameterSets, int pictureSets) {
    var record = new ByteArrayOutputStream();
    record.writeBytes(new byte[] {1, 0x64, 0, 0x28});
    record.write(0xFC | (lengthBytes - 1));
    record.write(0xE0 | sequenceParameterSets);
    for (var i = 0; i < sequenceParameterSets; i++) {
      record.writeBytes(new byte[] {0, 2, 0x67, 0x64});
    }

    record.write(pictureSets);
    for (var i = 0; i < pictureSets; i++) {
      record.writeBytes(new byte[] {0, 2, 0x68, (byte) 0xEE});
    }

    return visual(type, box("avcC", record.toByteArray()));
  }

  /** An HEVC entry whose hvcC holds one NAL unit of each listed type behind these length bytes. */
  static byte[] hevc(String type, int lengthBytes, List<Integer> unitTypes) {
    var record = new ByteArrayOutputStream();
    record.writeBytes(new byte[21]);
    record.write(0x0C | (lengthBytes - 1));
    record.write(unitTypes.size());
    for (var unitType : unitTypes) {
      record.writeBytes(new byte[] {(byte) (0x80 | unitType), 0, 1, 0, 2, 0x40, 0x01});
    }

    return visual(type, box("hvcC", record.toByteArray()));
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
