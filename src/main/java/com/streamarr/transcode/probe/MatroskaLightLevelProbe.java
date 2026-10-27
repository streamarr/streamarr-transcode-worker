package com.streamarr.transcode.probe;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adds the MaxCLL and MaxFALL elements of a Matroska chosen video track to its ffprobe description,
 * under {@link #CONTAINER_LIGHT_LEVEL}, in the fields of ffprobe's Content light level metadata.
 * ffprobe reports a Matroska light level only when both elements are nonzero, so the probe reads
 * them itself when ffprobe reports none. A track that holds either element reports both, with 0 for
 * the other.
 */
@Slf4j
final class MatroskaLightLevelProbe {

  /** The key under which the chosen video stream's description holds the track's light level. */
  static final String CONTAINER_LIGHT_LEVEL = "matroska_light_level";

  private static final String CONTENT_LIGHT_LEVEL = "Content light level metadata";
  private static final long VIDEO = 0xE0L;
  private static final long COLOUR = 0x55B0L;
  private static final long MAX_CLL = 0x55BCL;
  private static final long MAX_FALL = 0x55BDL;

  private MatroskaLightLevelProbe() {}

  static void addTo(JsonNode ffprobeOutput, Path source) {
    var isMatroska =
        ProbedSource.MATROSKA_FORMAT.equals(
            ffprobeOutput.path("format").path("format_name").asString(""));
    var chosen = ChosenVideoStream.find(ffprobeOutput.path("streams"));
    if (!isMatroska
        || chosen.isEmpty()
        || !(chosen.get().description() instanceof ObjectNode description)
        || VideoStreamFacts.sideData(description, CONTENT_LIGHT_LEVEL).isPresent()) {
      return;
    }

    lightLevel(source, chosen.get().videoOrdinal())
        .ifPresent(
            level ->
                description
                    .putObject(CONTAINER_LIGHT_LEVEL)
                    .put("max_content", level.maxContent())
                    .put("max_average", level.maxAverage()));
  }

  private static Optional<LightLevel> lightLevel(Path source, int videoTrackOrdinal) {
    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      return MatroskaFile.TopLevel.read(channel)
          .tracks()
          .map(tracks -> MatroskaFile.videoTrack(tracks, videoTrackOrdinal))
          .flatMap(track -> MatroskaFile.child(track, VIDEO))
          .flatMap(video -> MatroskaFile.child(video, COLOUR))
          .flatMap(MatroskaLightLevelProbe::lightLevel);
    } catch (IOException
        | ContainerIndexException
        | BufferUnderflowException
        | IllegalArgumentException failure) {
      log.warn("The probe could not read the Matroska light level of {}", source, failure);
      return Optional.empty();
    }
  }

  private static Optional<LightLevel> lightLevel(ByteBuffer colour) {
    var maxContent = MatroskaFile.unsigned(colour, MAX_CLL);
    var maxAverage = MatroskaFile.unsigned(colour, MAX_FALL);
    if (maxContent.isEmpty() && maxAverage.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(new LightLevel(maxContent.orElse(0L), maxAverage.orElse(0L)));
  }

  private record LightLevel(long maxContent, long maxAverage) {}
}
