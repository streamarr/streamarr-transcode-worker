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
 * FFmpeg copies the packets of the attempt's video and audio streams, from a seek to the interval's
 * start for its length, to a framecrc on its standard output. It also reads where a seek to a
 * position lands. Each scan passes the bytes of every packet it read to the consumer as it reads
 * them.
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
    return read(
            scanCommand(interval), packet -> packet.isKeyframe() && interval.presents(packet), 1)
        .stream()
        .findFirst()
        .map(VideoPacket::times);
  }

  /**
   * The decode time of the first video packet that a seek to the position reads, where the job
   * attempt's own seek there would start reading; empty when the seek reads no video packet.
   */
  Optional<Duration> landingDecodeTime(Duration seekPosition) throws InterruptedException {
    var command =
        commandBuilder.buildSeekLandingCommand(
            request.sourcePath(), request.transcodeDecision(), seekPosition);
    return read(command, _ -> true, 1).stream().findFirst().map(VideoPacket::decodeTime);
  }

  /**
   * Every keyframe that decodes in the interval, in decode order; from zero, every keyframe that
   * decodes before the interval's end. FFmpeg's seek into an MPEG-TS source lands on decode times
   * and its duration ends at one, so a keyframe that presents in one interval and decodes in the
   * interval before it reaches only the earlier interval's scan.
   */
  List<Keyframe> keyframes(Duration from, Duration until) throws InterruptedException {
    var interval = new Interval(from, until);
    return read(
            scanCommand(interval),
            packet -> packet.isKeyframe() && interval.decodes(packet),
            Integer.MAX_VALUE)
        .stream()
        .map(VideoPacket::times)
        .toList();
  }

  private List<String> scanCommand(Interval interval) {
    return commandBuilder.buildKeyframeScanCommand(
        request.sourcePath(),
        request.transcodeDecision(),
        interval.from(),
        interval.until().minus(interval.from()));
  }

  // The scan runs on a thread of its own, so an interrupt ends the wait at once; the forcible end
  // of FFmpeg then ends the read.
  private List<VideoPacket> read(List<String> command, Predicate<VideoPacket> wanted, int limit)
      throws InterruptedException {
    Process process;
    try {
      process = launcher.launch(command, request.attemptId());
    } catch (IOException e) {
      throw new TranscodeException("FFmpeg could not start a keyframe scan", e);
    }

    var reading = new FutureTask<>(() -> readPackets(process, wanted, limit));
    Thread.ofVirtual().name("keyframe-scan-" + request.attemptId()).start(reading);
    try {
      return reading.get();
    } catch (ExecutionException e) {
      throw new TranscodeException("A keyframe scan failed", e.getCause());
    } finally {
      process.destroyForcibly();
    }
  }

  private List<VideoPacket> readPackets(Process process, Predicate<VideoPacket> wanted, int limit)
      throws IOException, InterruptedException {
    var packets = new ArrayList<VideoPacket>();
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

        readRecord(line, timeBase).filter(wanted).ifPresent(packets::add);
        if (packets.size() == limit) {
          return packets;
        }
      }
    }

    var exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new TranscodeException("A keyframe scan's FFmpeg exited with " + exitCode);
    }

    return packets;
  }

  // A packet without a presentation time counts at its decode time, as a raw H.264 stream's does.
  private Optional<VideoPacket> readRecord(String line, Optional<TimeBase> timeBase) {
    var packetRecord = RECORD.matcher(line);
    if (!packetRecord.find()) {
      return Optional.empty();
    }

    bytesRead.accept(Long.parseLong(packetRecord.group(4)));
    if (!VIDEO_STREAM.equals(packetRecord.group(1))) {
      return Optional.empty();
    }

    var decodeTime = Long.parseLong(packetRecord.group(2));
    var presentationTime = Long.parseLong(packetRecord.group(3));
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
        new VideoPacket(
            durationOf(timeBase.get(), presentationTime),
            durationOf(timeBase.get(), decodeTime),
            isKeyframe(packetRecord)));
  }

  // A keyframe that its container marks to decode but not to present, as an edit list marks its
  // preroll, still starts decoding.
  private static boolean isKeyframe(Matcher packetRecord) {
    var flags =
        Optional.ofNullable(packetRecord.group(5))
            .map(value -> Integer.parseUnsignedInt(value, 16))
            .orElse(KEYFRAME);
    return (flags & KEYFRAME) != 0;
  }

  private static Duration durationOf(TimeBase timeBase, long ticks) {
    return Duration.ofNanos(
        BigInteger.valueOf(ticks)
            .multiply(BigInteger.valueOf(timeBase.numerator()))
            .multiply(BigInteger.valueOf(NANOS_PER_SECOND))
            .divide(BigInteger.valueOf(timeBase.denominator()))
            .longValueExact());
  }

  private record VideoPacket(Duration presentationTime, Duration decodeTime, boolean isKeyframe) {

    Keyframe times() {
      return new Keyframe(presentationTime, decodeTime);
    }
  }

  private record Interval(Duration from, Duration until) {

    boolean presents(VideoPacket packet) {
      var time = packet.presentationTime();
      return time.compareTo(from) >= 0 && time.compareTo(until) < 0;
    }

    boolean decodes(VideoPacket packet) {
      var time = packet.decodeTime();
      return (from.isZero() || time.compareTo(from) >= 0) && time.compareTo(until) < 0;
    }
  }
}
