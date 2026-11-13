package com.streamarr.transcode.engine;

import java.util.HexFormat;
import java.util.List;

/** The boxes that an initialization segment's first video sample entry holds after its fields. */
final class SampleEntryBoxes {

  private SampleEntryBoxes() {}

  /** The body of every box of the type, in upper-case hex, in the order the entry holds them. */
  static List<String> bodiesOf(InitializationSegment segment, String type) {
    return CodecConfigurationReader.firstVideoSampleEntryOf(segment)
        .orElseThrow()
        .after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES)
        .children(type)
        .stream()
        .map(
            box -> {
              var body = new byte[box.body().remaining()];
              box.body().duplicate().get(body);
              return HexFormat.of().withUpperCase().formatHex(body);
            })
        .toList();
  }
}
