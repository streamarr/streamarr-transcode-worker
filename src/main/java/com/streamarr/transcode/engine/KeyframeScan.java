package com.streamarr.transcode.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.NonNull;

/**
 * Reads the chosen video stream's keyframes in one interval of the source, without decoding: an
 * FFmpeg copies the video's packets and the audio's, from a seek to the interval's start for its
 * length, to a framecrc on its standard output. Each scan passes the bytes of every packet it read
 * to the consumer as it reads them.
 */
@Builder
final class KeyframeScan {

  private static final Pattern TIME_BASE = Pattern.compile("^#tb 0: (\\d+)/(\\d+)$");
  private static final Pattern RECORD =
      Pattern.compile(
          "^(\\d+), *(-?\\d+), *(-?\\d+), *-?\\d+, *(\\d+), 0x[0-9a-f]{8}(?:, F=0x([0-9A-F]{1,8}))?");
  // What framecrc prints for a time the packet does not carry: FFmpeg's AV_NOPTS_VALUE.
  private static final long NO_TIME = Long.MIN_VALUE;
  private static final int KEYFRAME = 0x1;
  private static final int DISCARD = 0x4;
  private static final String VIDEO_STREAM = "0";
  private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

  @NonNull private final FfmpegCommandBuilder commandBuilder;
  @NonNull private final ProcessLauncher launcher;
  @NonNull private final TranscodeRequest request;
  @NonNull private final LongConsumer bytesRead;

  /** A keyframe on the attempt's zero-based timeline. */
  record Keyframe(Duration presentationTime, Duration decodeTime) {}

  /** The first keyframe that presents in the interval; FFmpeg stops reading once it finds one. */
  Optional<Keyframe> firstKeyframe(Duration from, Duration until) throws InterruptedException {
    var interval = new Interval(from, until);
    return read(interval, interval::presents, 1).stream().findFirst();
  }

  /**
   * Every keyframe that decodes in the interval, in decode order; from zero, every keyframe that
   * decodes before the interval's end. FFmpeg's seek into an MPEG-TS source lands on decode times
   * and its duration ends at one, so a keyframe that presents in one interval and decodes in the
   * interval before it reaches only the earlier interval's scan.
   */
  List<Keyframe> keyframes(Duration from, Duration until) throws InterruptedException {
    var interval = new Interval(from, until);
    return read(interval, interval::decodes, Integer.MAX_VALUE);
  }

  // The scan runs on a thread of its own, so an interrupt ends the wait at once; the forcible end
  // of FFmpeg then ends the read.
  private List<Keyframe> read(Interval interval, Predicate<Keyframe> inside, int limit)
      throws InterruptedException {
    var command =
        commandBuilder.buildKeyframeScanCommand(
            request.sourcePath(),
            request.transcodeDecision().videoDecision().streamIndex(),
            interval.from(),
            interval.until().minus(interval.from()));
    Process process;
    try {
      process = launcher.launch(command, request.attemptId());
    } catch (IOException e) {
      throw new TranscodeException("FFmpeg could not start a keyframe scan", e);
    }

    var reading = new FutureTask<>(() -> readKeyframes(process, inside, limit));
    Thread.ofVirtual().name("keyframe-scan-" + request.attemptId()).start(reading);
    try {
      return reading.get();
    } catch (ExecutionException e) {
      throw new TranscodeException("A keyframe scan failed", e.getCause());
    } finally {
      process.destroyForcibly();
    }
  }

  private List<Keyframe> readKeyframes(Process process, Predicate<Keyframe> inside, int limit)
      throws IOException, InterruptedException {
    var keyframes = new ArrayList<Keyframe>();
    var timeBase = Optional.<TimeBase>empty();
    try (var output =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
      for (var line = output.readLine(); line != null; line = output.readLine()) {
        var timeBaseLine = TIME_BASE.matcher(line);
        if (timeBaseLine.matches()) {
          timeBase =
              Optional.of(
                  new TimeBase(
                      Long.parseLong(timeBaseLine.group(1)),
                      Long.parseLong(timeBaseLine.group(2))));
          continue;
        }

        var keyframe = readRecord(line, timeBase).filter(inside);
        keyframe.ifPresent(keyframes::add);
        if (keyframes.size() == limit) {
          return keyframes;
        }
      }
    }

    var exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new TranscodeException("A keyframe scan's FFmpeg exited with " + exitCode);
    }

    return keyframes;
  }

  // A packet without a presentation time counts at its decode time, as a raw H.264 stream's does.
  private Optional<Keyframe> readRecord(String line, Optional<TimeBase> timeBase) {
    var record = RECORD.matcher(line);
    if (!record.find()) {
      return Optional.empty();
    }

    bytesRead.accept(Long.parseLong(record.group(4)));
    if (!VIDEO_STREAM.equals(record.group(1)) || !isKeyframe(record)) {
      return Optional.empty();
    }

    var decodeTime = Long.parseLong(record.group(2));
    var presentationTime = Long.parseLong(record.group(3));
    if (presentationTime == NO_TIME) {
      presentationTime = decodeTime;
    }

    if (decodeTime == NO_TIME) {
      decodeTime = presentationTime;
    }

    if (presentationTime == NO_TIME || timeBase.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        new Keyframe(
            durationOf(timeBase.get(), presentationTime), durationOf(timeBase.get(), decodeTime)));
  }

  private static boolean isKeyframe(Matcher record) {
    var flags =
        Optional.ofNullable(record.group(5))
            .map(value -> Integer.parseUnsignedInt(value, 16))
            .orElse(KEYFRAME);
    return (flags & KEYFRAME) != 0 && (flags & DISCARD) == 0;
  }

  private static Duration durationOf(TimeBase timeBase, long ticks) {
    return Duration.ofNanos(
        BigInteger.valueOf(ticks)
            .multiply(BigInteger.valueOf(timeBase.numerator()))
            .multiply(BigInteger.valueOf(NANOS_PER_SECOND))
            .divide(BigInteger.valueOf(timeBase.denominator()))
            .longValueExact());
  }

  private record Interval(Duration from, Duration until) {

    boolean presents(Keyframe keyframe) {
      var time = keyframe.presentationTime();
      return time.compareTo(from) >= 0 && time.compareTo(until) < 0;
    }

    boolean decodes(Keyframe keyframe) {
      var time = keyframe.decodeTime();
      return (from.isZero() || time.compareTo(from) >= 0) && time.compareTo(until) < 0;
    }
  }
}
