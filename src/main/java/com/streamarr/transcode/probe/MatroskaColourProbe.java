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
 * Adds what the Colour element of a Matroska chosen video track holds of its static HDR metadata to
 * the track's ffprobe description, when ffprobe reports none. ffprobe reports a Matroska light
 * level only when MaxCLL and MaxFALL are both nonzero, so the probe adds the elements under {@link
 * #CONTAINER_LIGHT_LEVEL}, in the fields of ffprobe's Content light level metadata. A track that
 * holds either element reports both, with 0 for the other. FFmpeg's Matroska demuxer drops a
 * MasteringMetadata element whose primaries or luminance are incomplete, so the probe sets {@link
 * #CONTAINER_MASTERING_METADATA} when the track holds a MasteringMetadata element.
 */
@Slf4j
final class MatroskaColourProbe {

  /** The key under which the chosen video stream's description holds the track's light level. */
  static final String CONTAINER_LIGHT_LEVEL = "matroska_light_level";

  /** The key whose true value says that the chosen video track holds a MasteringMetadata. */
  static final String CONTAINER_MASTERING_METADATA = "matroska_mastering_metadata";

  private static final String CONTENT_LIGHT_LEVEL = "Content light level metadata";
  private static final String MASTERING_DISPLAY = "Mastering display metadata";
  private static final long VIDEO = 0xE0L;
  private static final long COLOUR = 0x55B0L;
  private static final long MAX_CLL = 0x55BCL;
  private static final long MAX_FALL = 0x55BDL;
  private static final long MASTERING_METADATA = 0x55D0L;

  private MatroskaColourProbe() {}

  static void addTo(JsonNode ffprobeOutput, Path source) {
    var isMatroska =
        ProbedSource.MATROSKA_FORMAT.equals(
            ffprobeOutput.path("format").path("format_name").asString(""));
    var chosen = ChosenVideoStream.find(ffprobeOutput.path("streams"));
    if (!isMatroska
        || chosen.isEmpty()
        || !(chosen.get().description() instanceof ObjectNode description)) {
      return;
    }

    var needsLightLevel = VideoStreamFacts.sideData(description, CONTENT_LIGHT_LEVEL).isEmpty();
    var needsMasteringDisplay = VideoStreamFacts.sideData(description, MASTERING_DISPLAY).isEmpty();
    if (!needsLightLevel && !needsMasteringDisplay) {
      return;
    }

    var colour = colour(source, chosen.get().videoOrdinal());
    if (needsLightLevel) {
      colour
          .flatMap(Colour::lightLevel)
          .ifPresent(
              level ->
                  description
                      .putObject(CONTAINER_LIGHT_LEVEL)
                      .put("max_content", level.maxContent())
                      .put("max_average", level.maxAverage()));
    }

    if (needsMasteringDisplay && colour.filter(Colour::hasMasteringMetadata).isPresent()) {
      description.put(CONTAINER_MASTERING_METADATA, true);
    }
  }

  private static Optional<Colour> colour(Path source, int videoTrackOrdinal) {
    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      return MatroskaFile.TopLevel.tracks(channel)
          .map(tracks -> MatroskaFile.videoTrack(tracks, videoTrackOrdinal))
          .flatMap(track -> MatroskaFile.child(track, VIDEO))
          .flatMap(video -> MatroskaFile.child(video, COLOUR))
          .map(
              colour ->
                  new Colour(
                      lightLevel(colour),
                      MatroskaFile.child(colour, MASTERING_METADATA).isPresent()));
    } catch (IOException
        | ContainerIndexException
        | BufferUnderflowException
        | IllegalArgumentException failure) {
      log.warn("The probe could not read the Matroska Colour element of {}", source, failure);
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

  private record Colour(Optional<LightLevel> lightLevel, boolean hasMasteringMetadata) {}
}
