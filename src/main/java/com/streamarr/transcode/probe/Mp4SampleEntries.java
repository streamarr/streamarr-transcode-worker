package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.Mp4MovieBox.child;
import static com.streamarr.transcode.probe.Mp4MovieBox.children;
import static com.streamarr.transcode.probe.Mp4MovieBox.skip;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adds to the ffprobe description of each H.264 and HEVC track of an MP4 or MOV file the number of
 * sample entries in its sample description box (stsd), under {@link #KEY}. ffprobe describes a
 * track by its first sample entry and does not report the others. The probe adds nothing when it
 * cannot read the movie box or finds no single track with the stream's ID.
 */
@Slf4j
final class Mp4SampleEntries {

  /** The key under which a stream's description holds its track's number of sample entries. */
  static final String KEY = "mp4_sample_entries";

  private Mp4SampleEntries() {}

  static void addTo(JsonNode ffprobeOutput, Path source) {
    if (!ProbedSource.MP4_FORMAT.equals(
        ffprobeOutput.path("format").path("format_name").asString(""))) {
      return;
    }

    var streams = new HashMap<Long, ObjectNode>();
    for (var stream : ffprobeOutput.path("streams")) {
      var family = ProbedCodecFamily.of(stream);
      if ((family == ProbedCodecFamily.H264 || family == ProbedCodecFamily.HEVC)
          && stream instanceof ObjectNode description) {
        ChosenVideoStream.trackId(stream).ifPresent(id -> streams.put(id, description));
      }
    }

    if (streams.isEmpty()) {
      return;
    }

    read(source)
        .ifPresent(
            counts ->
                streams.forEach(
                    (trackId, description) ->
                        Optional.ofNullable(counts.get(trackId))
                            .ifPresent(count -> description.put(KEY, count))));
  }

  /**
   * The number of sample entries of each track, by track ID. FFmpeg reads every trak, so an ID that
   * several traks carry names no single track and gets no count.
   */
  private static Optional<Map<Long, Long>> read(Path source) {
    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      var tracks = children(Mp4MovieBox.read(channel), "trak");
      var trackIds = tracks.stream().map(Mp4MovieBox::trackId).toList();
      var counts = new HashMap<Long, Long>();
      for (var index = 0; index < tracks.size(); index++) {
        var trackId = trackIds.get(index);
        if (Collections.frequency(trackIds, trackId) == 1) {
          sampleDescription(tracks.get(index))
              .ifPresent(box -> counts.put(trackId, entryCount(box)));
        }
      }

      return Optional.of(counts);
    } catch (IOException | ContainerIndexException | BufferUnderflowException failure) {
      log.warn("The probe could not read the MP4 sample entries of {}", source, failure);
      return Optional.empty();
    }
  }

  private static Optional<ByteBuffer> sampleDescription(ByteBuffer track) {
    return child(track, "mdia")
        .flatMap(media -> child(media, "minf"))
        .flatMap(info -> child(info, "stbl"))
        .flatMap(table -> child(table, "stsd"));
  }

  // The entry_count follows the box's version and flags.
  private static long entryCount(ByteBuffer sampleDescription) {
    skip(sampleDescription, 4);
    return Integer.toUnsignedLong(sampleDescription.getInt());
  }
}
