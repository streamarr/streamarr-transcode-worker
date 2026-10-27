package com.streamarr.transcode.probe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adds the bytes of the first packets of an H.264 or HEVC chosen video stream to its ffprobe
 * description, under {@link #FIRST_PACKETS}, as ffprobe's hex dumps: as many packets as the read of
 * its first frames took, so that they hold those frames. FFmpeg's H.264 decoder drops the NAL units
 * that carry Dolby Vision RPUs, and its HEVC decoder ignores an RPU that is not the last NAL unit
 * of its sample, so the probe finds them in the packets. When ffprobe fails or prints no packet,
 * the description gets no packets.
 */
@RequiredArgsConstructor
@Slf4j
final class FirstPacketsProbe {

  /** The key under which the chosen video stream's description holds its first packets. */
  static final String FIRST_PACKETS = "first_packets";

  @NonNull private final ObjectMapper objectMapper;
  @NonNull private final ContainerIndexProbe.FfprobeRunner ffprobe;

  /** Adds the first {@code count} packets, as many as the read of the first frames took. */
  void addTo(JsonNode ffprobeOutput, Path source, int count)
      throws ExecutionException, InterruptedException {
    var chosen = ChosenVideoStream.find(ffprobeOutput.path("streams"));
    if (chosen.isEmpty()
        || !(chosen.get().description() instanceof ObjectNode description)
        || NalSyntax.of(description.path("codec_name").asString("")).isEmpty()) {
      return;
    }

    var arguments = new ArrayList<>(List.of("-v", "quiet", "-print_format", "json"));
    arguments.addAll(FfprobeExecutor.READ_LIMITS);
    arguments.addAll(
        List.of(
            "-select_streams",
            String.valueOf(chosen.get().streamIndex()),
            "-read_intervals",
            "%+#" + count,
            "-show_entries",
            "packet=data",
            "-show_data",
            source.toString()));
    var output = ffprobe.run(arguments);
    if (output.exitCode() != 0) {
      log.warn("ffprobe's first-packets read of {} exited with code {}", source, output.exitCode());
      return;
    }

    try {
      var packets = objectMapper.readTree(output.stdout()).path("packets");
      if (packets instanceof ArrayNode list && !list.isEmpty()) {
        description.set(FIRST_PACKETS, list);
      }
    } catch (JacksonException failure) {
      log.warn("ffprobe's first-packets read of {} printed no JSON", source, failure);
    }
  }
}
