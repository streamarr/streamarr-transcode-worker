package com.streamarr.transcode.fakes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * An index-less source, standing in for FFmpeg's keyframe scans of it. Its video has a picture
 * every 250 ms up to its end, keyframes at the given presentation times, each decoded 83 ms before
 * it presents, and an audio packet every 250 ms. Each scan writes the framecrc of the packets that
 * decode inside the interval that its command's seek and duration name, as FFmpeg's scan of an
 * MPEG-TS source does: the seek lands on the packets' decode times, and the duration ends at a
 * decode time. A scan without a seek reads every packet that decodes before its end.
 */
public final class ScriptedKeyframeSource {

  /** How long before it presents each picture decodes, as B-frames make it. */
  public static final Duration REORDER_DELAY = Duration.ofMillis(83);

  private static final int VIDEO_PACKET_BYTES = 1_000;
  private static final int AUDIO_PACKET_BYTES = 300;
  private static final long PACKET_MILLIS = 250;

  private final List<Duration> keyframes;
  private final int videoEndSeconds;
  private final List<List<Duration>> scanned = new ArrayList<>();
  private long bytesWritten;

  public ScriptedKeyframeSource(List<Duration> keyframes, int videoEndSeconds) {
    this.keyframes = List.copyOf(keyframes);
    this.videoEndSeconds = videoEndSeconds;
  }

  /** Keyframes every 2 s from 0 s up to and including the last. */
  public static List<Duration> keyframesEveryTwoSecondsUpTo(int lastKeyframeSeconds) {
    return IntStream.rangeClosed(0, lastKeyframeSeconds / 2)
        .mapToObj(index -> Duration.ofSeconds(index * 2L))
        .toList();
  }

  /** The scan's framecrc of the interval that the command names, as a process that writes it. */
  public ScriptedProcess scan(List<String> command) {
    return ScriptedProcess.builder().output(framecrcOf(command)).build();
  }

  /** The framecrc that a scan with the command writes. */
  public synchronized byte[] framecrcOf(List<String> command) {
    var seek = argumentAfter(command, "-ss");
    var from = seek.orElse(Duration.ZERO);
    var until = from.plus(argumentAfter(command, "-t").orElseThrow());
    scanned.add(List.of(from, until));
    var framecrc = new StringBuilder("#tb 0: 1/1000\n#tb 1: 1/1000\n");
    var pictures = new ArrayList<>(keyframes);
    for (var millis = 0L; millis < videoEndSeconds * 1_000L; millis += PACKET_MILLIS) {
      pictures.add(Duration.ofMillis(millis));
    }

    pictures.stream()
        .distinct()
        .sorted()
        .filter(time -> decodesInside(time.minus(REORDER_DELAY), seek, until))
        .forEach(time -> framecrc.append(videoRecord(time)));
    for (var millis = from.toMillis(); millis < until.toMillis(); millis += PACKET_MILLIS) {
      framecrc.append(
          "1, %d, %d, %d, %d, 0x0a1b2c3d\n"
              .formatted(millis, millis, PACKET_MILLIS, AUDIO_PACKET_BYTES));
      bytesWritten += AUDIO_PACKET_BYTES;
    }

    return framecrc.toString().getBytes(StandardCharsets.US_ASCII);
  }

  /** Each scanned interval's start and end, in scan order. */
  public synchronized List<List<Duration>> scannedIntervals() {
    return List.copyOf(scanned);
  }

  /** The bytes of every packet that the scans have written. */
  public synchronized long bytesWritten() {
    return bytesWritten;
  }

  private String videoRecord(Duration presentationTime) {
    bytesWritten += VIDEO_PACKET_BYTES;
    var flags = keyframes.contains(presentationTime) ? "" : ", F=0x0";
    return "0, %d, %d, %d, %d, 0x0a1b2c3d%s\n"
        .formatted(
            presentationTime.minus(REORDER_DELAY).toMillis(),
            presentationTime.toMillis(),
            PACKET_MILLIS,
            VIDEO_PACKET_BYTES,
            flags);
  }

  private static boolean decodesInside(
      Duration decodeTime, Optional<Duration> seek, Duration until) {
    return decodeTime.compareTo(until) < 0
        && seek.map(from -> decodeTime.compareTo(from) >= 0).orElse(true);
  }

  private static Optional<Duration> argumentAfter(List<String> command, String option) {
    var index = command.indexOf(option);
    if (index < 0) {
      return Optional.empty();
    }

    return Optional.of(
        Duration.ofNanos(
            new BigDecimal(command.get(index + 1)).movePointRight(9).longValueExact()));
  }
}
