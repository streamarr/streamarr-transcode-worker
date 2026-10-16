package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.number;
import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.ProbeContainerInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.google.protobuf.Duration;
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
    var media = ProbeMediaInfo.newBuilder().setContainer(container);
    var streams = root.path("streams");
    if (!streams.isMissingNode() && !streams.isNull() && !streams.isArray()) {
      throw new IllegalArgumentException("ffprobe returned a malformed stream list");
    }

    for (var stream : streams) {
      media.addStreams(parseStream(stream, media.getStreamsCount()));
    }

    ChosenVideoStream.find(streams)
        .ifPresent(chosen -> media.setChosenVideoStreamIndex(chosen.streamIndex()));
    return media.build();
  }

  private static ProbeStreamInfo parseStream(JsonNode source, int position) {
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
            .setIsDefault(source.path("disposition").path("default").asInt() == 1)
            .setIsForced(source.path("disposition").path("forced").asInt() == 1);
    text(source, "codec_name").ifPresent(stream::setCodec);
    text(source.path("tags"), "language").ifPresent(stream::setLanguage);
    number(source, "bit_rate", Long::parseLong).ifPresent(stream::setBitrateBitsPerSecond);
    number(source, "width", Integer::parseInt).ifPresent(stream::setWidth);
    number(source, "height", Integer::parseInt).ifPresent(stream::setHeight);
    number(source, "r_frame_rate", ProbeMediaReader::framerate)
        .filter(value -> Double.isFinite(value) && value > 0)
        .ifPresent(stream::setFramerate);
    if ("audio".equals(type)) {
      number(source, "channels", Integer::parseInt).ifPresent(stream::setChannels);
    }

    return stream.build();
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
