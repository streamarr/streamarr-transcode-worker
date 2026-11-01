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

  /** An mp4a entry whose esds holds an AudioSpecificConfig. */
  static byte[] mp4aWithEsds() {
    return audio("mp4a", 0, box("esds", int32(0), new byte[] {3, 5, 0, 1, 0, 4, 0}));
  }

  /** A QuickTime version 1 mp4a entry that wraps its esds in a wave box. */
  static byte[] quickTimeMp4aWithEsdsInWave() {
    return audio("mp4a", 1, box("wave", box("esds", int32(0), new byte[] {3, 0})));
  }

  /** An audio entry of this type with a sound description version and these child boxes. */
  static byte[] audio(String type, int version, byte[]... children) {
    var fields = new byte[28 + (version == 1 ? 16 : 0) + (version == 2 ? 36 : 0)];
    fields[9] = (byte) version;
    return box(type, concat(fields, concat(children)));
  }
}
