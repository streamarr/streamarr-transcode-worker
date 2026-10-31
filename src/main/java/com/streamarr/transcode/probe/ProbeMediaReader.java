package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.number;
import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.ProbeContainerInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.google.protobuf.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Reads the container and stream facts of a successful probe from ffprobe's JSON output. */
final class ProbeMediaReader {

  private ProbeMediaReader() {}

  static ProbeMediaInfo read(JsonNode root) {
    var container = ProbeContainerInfo.newBuilder();
    var format = root.path("format");
    text(format, "format_name").ifPresent(container::setFormat);
    number(format, "bit_rate", Long::parseLong).ifPresent(container::setBitrateBitsPerSecond);
    number(format, "duration", Double::parseDouble)
        .filter(value -> Double.isFinite(value) && value >= 0)
        .map(ProbeMediaReader::duration)
        .ifPresent(container::setDuration);
    var timeline = ProbeTimeline.of(format);
    ProbeChapters.add(root.path("chapters"), timeline, container);
    var endTimes = StreamEndTime.in(format, timeline);
    var media = ProbeMediaInfo.newBuilder().setContainer(container);
    var streams = root.path("streams");
    if (!streams.isMissingNode() && !streams.isNull() && !streams.isArray()) {
      throw new IllegalArgumentException("ffprobe returned a malformed stream list");
    }

    var statisticsBitrates = MatroskaStatistics.bitrates(format, streams);
    var parsed = new ArrayList<ProbeStreamInfo.Builder>();
    var labels = new IdentityHashMap<ProbeStreamInfo.Builder, StreamLabels>();
    for (var stream : streams) {
      var parsedStream = parseStream(stream, parsed.size(), endTimes);
      Optional.ofNullable(statisticsBitrates.get(parsed.size()))
          .filter(_ -> !parsedStream.hasBitrateBitsPerSecond())
          .ifPresent(parsedStream::setBitrateBitsPerSecond);
      parsed.add(parsedStream);
      if (isLabeled(parsedStream.getCodecType())) {
        labels.put(parsedStream, StreamLabels.of(stream));
      }
    }

    addLabels(media, labels);
    parsed.forEach(media::addStreams);
    ChosenVideoStream.find(streams)
        .ifPresent(chosen -> media.setChosenVideoStreamIndex(chosen.streamIndex()));
    return media.build();
  }

  private static boolean isLabeled(String codecType) {
    return "audio".equals(codecType) || "subtitle".equals(codecType);
  }

  /**
   * The titles and handler names of one result take at most {@link
   * StreamLabels#MAXIMUM_ENCODED_BYTES}, so that a file's text cannot fill the session message.
   */
  private static void addLabels(
      ProbeMediaInfo.Builder media, Map<ProbeStreamInfo.Builder, StreamLabels> labels) {
    var encodedBytes = labels.values().stream().mapToInt(StreamLabels::encodedBytes).sum();
    if (encodedBytes > StreamLabels.MAXIMUM_ENCODED_BYTES) {
      media.setStreamTitlesOmitted(true);
      return;
    }

    labels.forEach((stream, streamLabels) -> streamLabels.addTo(stream));
  }

  private static ProbeStreamInfo.Builder parseStream(
      JsonNode source, int position, StreamEndTime endTimes) {
    var type =
        Optional.ofNullable(source.get("codec_type"))
            .filter(JsonNode::isTextual)
            .map(JsonNode::asString)
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () -> new IllegalArgumentException("ffprobe stream is missing codec_type"));
    var stream =
        ProbeStreamInfo.newBuilder()
            .setIndex(text(source, "index").map(Integer::parseInt).orElse(position))
            .setCodecType(type)
            .setIsDefault(disposition(source, "default").asInt() == 1)
            .setIsForced(disposition(source, "forced").asInt() == 1);
    text(source, "codec_name").ifPresent(stream::setCodec);
    text(source.path("tags"), "language").ifPresent(stream::setLanguage);
    number(source, "bit_rate", Long::parseLong).ifPresent(stream::setBitrateBitsPerSecond);
    number(source, "width", Integer::parseInt).ifPresent(stream::setWidth);
    number(source, "height", Integer::parseInt).ifPresent(stream::setHeight);
    number(source, "r_frame_rate", ProbeMediaReader::framerate)
        .filter(value -> Double.isFinite(value) && value > 0)
        .ifPresent(stream::setFramerate);
    switch (type) {
      case "video" -> {
        VideoStreamFacts.add(source, stream);
        CodecStringReader.read(source).ifPresent(stream::setCodecString);
        endTimes.of(source).ifPresent(stream::setEndTime);
      }
      case "audio" -> {
        number(source, "channels", Integer::parseInt).ifPresent(stream::setChannels);
        number(source, "sample_rate", Integer::parseInt)
            .filter(rate -> rate > 0)
            .ifPresent(stream::setSampleRateHz);
        text(source, "profile").ifPresent(stream::setAudioProfile);
        CodecStringReader.read(source).ifPresent(stream::setCodecString);
        endTimes.of(source).ifPresent(stream::setEndTime);
        hearingImpaired(source).ifPresent(stream::setIsHearingImpaired);
      }
      case "subtitle" -> hearingImpaired(source).ifPresent(stream::setIsHearingImpaired);
      default -> {
        // Other stream types report only the facts that every stream reports.
      }
    }

    return stream;
  }

  private static JsonNode disposition(JsonNode source, String flag) {
    return source.path("disposition").path(flag);
  }

  private static Optional<Boolean> hearingImpaired(JsonNode source) {
    var flag = disposition(source, "hearing_impaired");
    return flag.isIntegralNumber() ? Optional.of(flag.asInt() == 1) : Optional.empty();
  }

  private static double framerate(String value) {
    var parts = value.split("/");
    return parts.length == 2
        ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1])
        : Double.parseDouble(value);
  }

  private static Duration duration(double seconds) {
    var millis = (long) (seconds * 1000);
    return Duration.newBuilder()
        .setSeconds(millis / 1000)
        .setNanos((int) (millis % 1000) * 1_000_000)
        .build();
  }
}
