package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.number;
import static com.streamarr.transcode.probe.FfprobeJson.text;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The bitrates that the Matroska statistics tags of the video and audio streams state. mkvmerge
 * writes BPS, DURATION, NUMBER_OF_FRAMES and NUMBER_OF_BYTES for each stream, which ffprobe reports
 * as stream tags, and names itself in each stream's {@code _STATISTICS_WRITING_APP} and in the
 * segment's WritingApp. FFmpeg copies the tags when it rewrites the file, even when it cuts the
 * file or re-encodes a stream, and names itself as the WritingApp. So a stream's tags count only
 * when both of these hold:
 *
 * <ul>
 *   <li>Its {@code _STATISTICS_WRITING_APP} equals the WritingApp that {@link MatroskaWritingApp}
 *       read, or names mkvpropedit while the WritingApp names mkvmerge. mkvpropedit writes fresh
 *       statistics into a file without naming itself as its WritingApp, and changes no media data.
 *   <li>The NUMBER_OF_BYTES of every stream whose tags pass add up to no more than the file's size.
 *       Otherwise no stream's tags count.
 * </ul>
 */
final class MatroskaStatistics {

  /** The statistics tag that names the program that wrote a stream's statistics. */
  static final String WRITING_APP = "_STATISTICS_WRITING_APP";

  private static final BigDecimal BITS_PER_BYTE = BigDecimal.valueOf(8);
  private static final BigDecimal LARGEST_BITRATE = BigDecimal.valueOf(Long.MAX_VALUE);

  private MatroskaStatistics() {}

  /**
   * Each trusted video or audio stream's bitrate in bits per second, by the stream's position in
   * ffprobe's stream list. A stream's bitrate is its BPS tag, or else NUMBER_OF_BYTES × 8 /
   * DURATION.
   */
  static Map<Integer, Long> bitrates(JsonNode format, JsonNode streams) {
    var fileWritingApp = text(format, MatroskaWritingApp.KEY);
    var fileSize = number(format, "size", Long::parseLong);
    if (fileWritingApp.isEmpty() || fileSize.isEmpty()) {
      return Map.of();
    }

    var countedBytes = BigInteger.ZERO;
    var bitrates = new HashMap<Integer, Long>();
    for (var position = 0; position < streams.size(); position++) {
      var stream = streams.get(position);
      var bytes = trustedBytes(stream, fileWritingApp.get());
      if (bytes.isEmpty()) {
        continue;
      }

      countedBytes = countedBytes.add(BigInteger.valueOf(bytes.get()));
      var index = position;
      bitsPerSecond(stream, bytes.get()).ifPresent(bitrate -> bitrates.put(index, bitrate));
    }

    return countedBytes.compareTo(BigInteger.valueOf(fileSize.get())) > 0 ? Map.of() : bitrates;
  }

  private static Optional<Long> trustedBytes(JsonNode stream, String fileWritingApp) {
    var isTrusted =
        StreamTags.matroskaStatistic(stream, WRITING_APP)
            .filter(
                statisticsApp ->
                    statisticsApp.equals(fileWritingApp)
                        || (statisticsApp.startsWith("mkvpropedit ")
                            && fileWritingApp.startsWith("mkvmerge ")))
            .isPresent();
    if (!isTrusted) {
      return Optional.empty();
    }

    return StreamTags.matroskaStatistic(stream, "NUMBER_OF_BYTES")
        .flatMap(MatroskaStatistics::count);
  }

  private static Optional<Long> bitsPerSecond(JsonNode stream, long bytes) {
    var type = text(stream, "codec_type").orElse("");
    if (!type.equals("video") && !type.equals("audio")) {
      return Optional.empty();
    }

    var tagged =
        StreamTags.matroskaStatistic(stream, "BPS")
            .flatMap(MatroskaStatistics::count)
            .filter(bitsPerSecond -> bitsPerSecond > 0);
    if (tagged.isPresent()) {
      return tagged;
    }

    return StreamTags.matroskaDuration(stream)
        .filter(seconds -> seconds.signum() > 0)
        .map(
            seconds ->
                BigDecimal.valueOf(bytes)
                    .multiply(BITS_PER_BYTE)
                    .divide(seconds, 0, RoundingMode.HALF_UP))
        .filter(bitsPerSecond -> bitsPerSecond.signum() > 0)
        .filter(bitsPerSecond -> bitsPerSecond.compareTo(LARGEST_BITRATE) <= 0)
        .map(BigDecimal::longValueExact);
  }

  private static Optional<Long> count(String value) {
    try {
      return Optional.of(Long.parseLong(value)).filter(count -> count >= 0);
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }
}
