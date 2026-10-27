package com.streamarr.transcode.probe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adds the side data of the chosen video stream's first 3 frames to its ffprobe description, under
 * {@link #FIRST_FRAMES}. ffprobe limits a read by packets, and a packet can decode to no frame, as
 * for a packet ahead of an MP4 edit list. So the probe widens the read until it decodes 3 frames,
 * up to {@link #WIDEST_READ} packets, and keeps the first 3. When ffprobe fails or prints no frame,
 * the description gets no frames: the probe then read none. When a read fails before the probe
 * decodes 3 frames, or the widest read decodes fewer than 3 frames from a stream that holds more
 * packets than it read, the description also gets {@link #FIRST_FRAMES_CUT_SHORT}: the stream may
 * hold frames that the probe did not read.
 */
@RequiredArgsConstructor
@Slf4j
final class FirstFramesProbe {

  /** The key under which the chosen video stream's description holds its first frames. */
  static final String FIRST_FRAMES = "first_frames";

  /**
   * The key whose true value says that a failed read, or the widest read's limit, kept the probe
   * from decoding 3 frames.
   */
  static final String FIRST_FRAMES_CUT_SHORT = "first_frames_cut_short";

  private static final int FRAME_COUNT = 3;
  private static final int WIDER_READ_FACTOR = 4;
  private static final int WIDEST_READ = 192;

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

  /**
   * Adds the first frames, and returns the count of the first packets that the read of those frames
   * took; empty when the probe read no frame.
   */
  OptionalInt addTo(JsonNode ffprobeOutput, Path source)
      throws ExecutionException, InterruptedException {
    var chosen = ChosenVideoStream.find(ffprobeOutput.path("streams"));
    if (chosen.isEmpty() || !(chosen.get().description() instanceof ObjectNode description)) {
      return OptionalInt.empty();
    }

    var reads = new StreamReads(source, chosen.get().streamIndex());
    var frames = List.<JsonNode>of();
    var framesPackets = 0;
    var readFailed = false;
    for (var packets = FRAME_COUNT;
        packets <= WIDEST_READ && frames.size() < FRAME_COUNT;
        packets *= WIDER_READ_FACTOR) {
      var wider = read(reads, packets);
      readFailed |= wider.isEmpty();
      if (wider.orElse(List.of()).size() > frames.size()) {
        frames = wider.get();
        framesPackets = packets;
      }
    }

    if (frames.isEmpty()) {
      return OptionalInt.empty();
    }

    description.set(
        FIRST_FRAMES,
        objectMapper
            .createArrayNode()
            .addAll(frames.subList(0, Math.min(FRAME_COUNT, frames.size()))));
    if (frames.size() < FRAME_COUNT && (readFailed || holdsPacketsPastWidestRead(reads))) {
      description.put(FIRST_FRAMES_CUT_SHORT, true);
    }

    return OptionalInt.of(framesPackets);
  }

  /**
   * Whether the stream holds more packets than the widest read takes, so that an edit list's
   * preroll, which can discard most of them, can hide frames past the widest read. True when
   * ffprobe cannot count them.
   */
  private boolean holdsPacketsPastWidestRead(StreamReads reads)
      throws ExecutionException, InterruptedException {
    var output = ffprobe.run(reads.firstPackets(WIDEST_READ + 1, "packet=stream_index"));
    if (output.exitCode() != 0) {
      log.warn(
          "ffprobe's packet count of {} exited with code {}", reads.source(), output.exitCode());
      return true;
    }

    try {
      return objectMapper.readTree(output.stdout()).path("packets").size() > WIDEST_READ;
    } catch (JacksonException failure) {
      log.warn("ffprobe's packet count of {} printed no JSON", reads.source(), failure);
      return true;
    }
  }

  /** The frames that the stream's first packets decode to; empty when ffprobe fails. */
  private Optional<List<JsonNode>> read(StreamReads reads, int packets)
      throws ExecutionException, InterruptedException {
    var output = ffprobe.run(reads.firstPackets(packets, FRAME_ENTRIES));
    if (output.exitCode() != 0) {
      log.warn(
          "ffprobe's first-frames read of {} exited with code {}",
          reads.source(),
          output.exitCode());
      return Optional.empty();
    }

    try {
      return Optional.of(
          objectMapper.readTree(output.stdout()).path("frames").valueStream().toList());
    } catch (JacksonException failure) {
      log.warn("ffprobe's first-frames read of {} printed no JSON", reads.source(), failure);
      return Optional.empty();
    }
  }

  /** ffprobe's reads of one stream of a source. */
  private record StreamReads(Path source, int streamIndex) {

    /** The arguments that print the entries of the stream's first {@code packets} packets. */
    List<String> firstPackets(int packets, String entries) {
      var arguments = new ArrayList<>(List.of("-v", "quiet", "-print_format", "json"));
      arguments.addAll(FfprobeExecutor.READ_LIMITS);
      arguments.addAll(
          List.of(
              "-select_streams",
              String.valueOf(streamIndex),
              "-read_intervals",
              "%+#" + packets,
              "-show_entries",
              entries,
              source.toString()));
      return arguments;
    }
  }
}
