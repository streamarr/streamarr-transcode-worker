package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.number;
import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Adds probe version 7's audio facts to a stream's probe result: the channel layout, sample format
 * and bit depth as ffprobe prints them, seven dispositions, and a FLAC stream's minimum block size.
 */
final class AudioStreamFacts {

  // ffprobe prints this word for a layout or sample format that it does not know.
  private static final String UNKNOWN = "unknown";

  // RFC 9639, section 8.2: the STREAMINFO block's length, which starts with its minimum block size.
  private static final int STREAMINFO_BYTES = 34;

  private AudioStreamFacts() {}

  static void addTo(JsonNode source, ProbeStreamInfo.Builder stream) {
    named(source, "channel_layout").ifPresent(stream::setChannelLayout);
    named(source, "sample_fmt").ifPresent(stream::setSampleFormat);
    positive(source, "bits_per_raw_sample")
        .or(() -> positive(source, "bits_per_sample"))
        .ifPresent(stream::setBitDepth);
    stream
        .setIsComment(isSet(source, "comment"))
        .setIsVisualImpaired(isSet(source, "visual_impaired"))
        .setIsDescriptions(isSet(source, "descriptions"))
        .setIsOriginal(isSet(source, "original"))
        .setIsDub(isSet(source, "dub"))
        .setIsCleanEffects(isSet(source, "clean_effects"))
        .setIsDependent(isSet(source, "dependent"));
    if (text(source, "codec_name").filter("flac"::equals).isPresent()) {
      Extradata.of(source)
          .filter(streaminfo -> streaminfo.length == STREAMINFO_BYTES)
          .map(streaminfo -> (streaminfo[0] & 0xff) << 8 | streaminfo[1] & 0xff)
          .ifPresent(stream::setFlacMinimumBlockSizeSamples);
    }
  }

  private static Optional<String> named(JsonNode source, String field) {
    return text(source, field).filter(name -> !name.isBlank() && !UNKNOWN.equals(name));
  }

  private static Optional<Integer> positive(JsonNode source, String field) {
    return number(source, field, Integer::parseInt).filter(bits -> bits > 0);
  }

  private static boolean isSet(JsonNode source, String disposition) {
    return source.path("disposition").path(disposition).asInt(0) == 1;
  }
}
