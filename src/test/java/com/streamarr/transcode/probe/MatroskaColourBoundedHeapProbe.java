package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probe;

import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Probes the Matroska file that its first argument names, in a JVM whose heap the caller bounds,
 * and writes the chosen video stream's two omitted flags to the file that its second argument
 * names.
 */
final class MatroskaColourBoundedHeapProbe {

  private MatroskaColourBoundedHeapProbe() {}

  static void main(String[] arguments) throws IOException {
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(
                        """
                        {"streams":[{"index":0,"codec_type":"video","codec_name":"hevc",
                          "time_base":"1/1000"}],"format":{"format_name":"matroska,webm"}}
                        """)
                    .source(Path.of(arguments[0]))
                    .build())
            .getStreams(0);
    Files.writeString(
        Path.of(arguments[1]),
        "mastering_display_omitted=%s content_light_level_omitted=%s"
            .formatted(video.getMasteringDisplayOmitted(), video.getContentLightLevelOmitted()));
  }
}
