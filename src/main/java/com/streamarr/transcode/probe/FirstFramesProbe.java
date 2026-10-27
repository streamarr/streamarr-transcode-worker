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
 * Adds the side data of the chosen video stream's first frames to its ffprobe description, under
 * {@link #FIRST_FRAMES}. ffprobe decodes the stream's first 3 packets, so it prints at most 3
 * frames. A decoder that discards a packet, as for a packet ahead of an MP4 edit list, prints
 * fewer. When ffprobe fails or prints no frame, the description gets no frames: the probe then read
 * none.
 */
@RequiredArgsConstructor
@Slf4j
final class FirstFramesProbe {

  /** The key under which the chosen video stream's description holds its first frames. */
  static final String FIRST_FRAMES = "first_frames";

  private static final String FRAME_COUNT = "%+#3";

  // Only the side data fields that the probe reads, so that a Dolby Vision frame's reshaping curves
  // stay out of the output.
  private static final String FRAME_ENTRIES =
      "frame=stream_index:frame_side_data=side_data_type,"
          + "red_x,red_y,green_x,green_y,blue_x,blue_y,white_point_x,white_point_y,"
          + "min_luminance,max_luminance,max_content,max_average,"
          + "vdr_rpu_profile,bl_video_full_range_flag,el_spatial_resampling_filter_flag,"
          + "disable_residual_flag,vdr_bit_depth";

  @NonNull private final ObjectMapper objectMapper;
  @NonNull private final ContainerIndexProbe.FfprobeRunner ffprobe;

  void addTo(JsonNode ffprobeOutput, Path source) throws ExecutionException, InterruptedException {
    var chosen = ChosenVideoStream.find(ffprobeOutput.path("streams"));
    if (chosen.isEmpty() || !(chosen.get().description() instanceof ObjectNode description)) {
      return;
    }

    var arguments = new ArrayList<>(List.of("-v", "quiet", "-print_format", "json"));
    arguments.addAll(FfprobeExecutor.READ_LIMITS);
    arguments.addAll(
        List.of(
            "-select_streams",
            String.valueOf(chosen.get().streamIndex()),
            "-read_intervals",
            FRAME_COUNT,
            "-show_entries",
            FRAME_ENTRIES,
            source.toString()));
    var output = ffprobe.run(arguments);
    if (output.exitCode() != 0) {
      log.warn("ffprobe's first-frames read of {} exited with code {}", source, output.exitCode());
      return;
    }

    try {
      var frames = objectMapper.readTree(output.stdout()).path("frames");
      if (frames instanceof ArrayNode list && !list.isEmpty()) {
        description.set(FIRST_FRAMES, list);
      }
    } catch (JacksonException failure) {
      log.warn("ffprobe's first-frames read of {} printed no JSON", source, failure);
    }
  }
}
