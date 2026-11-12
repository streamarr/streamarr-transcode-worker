package com.streamarr.transcode.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.NonNull;

/**
 * Reads the chosen video stream's keyframes in one interval of the source, without decoding: an
 * FFmpeg copies the packets of the attempt's video and audio streams, from a seek to the interval's
 * start for its length, to a framecrc on its standard output. It also checks which picture the
 * attempt's own read from a seek position shows first. Each scan and check passes the bytes of
 * every source packet that its framecrcs list to the consumer as it reads them.
 */
@Builder
final class KeyframeScan {

  private static final Pattern TIME_BASE = Pattern.compile("^#tb (\\d+): (\\d+)/(\\d+)$");
  private static final Pattern RECORD =
      Pattern.compile(
          "^(\\d+), *(-?\\d+), *(-?\\d+), *(-?\\d+), *(\\d+), 0x[0-9a-f]{8}(?:, F=0x([0-9A-F]{1,8}))?");
  // What framecrc prints for a time the packet does not carry: FFmpeg's AV_NOPTS_VALUE.
  private static final long NO_TIME = Long.MIN_VALUE;
  private static final int KEYFRAME = 0x1;
  // A scan, a landing check and a first picture check's copies write the chosen video stream's
  // packets as their stream 0. A first picture check writes the decoder's pictures as the stream 0
  // of its other output.
  private static final String COPIED_VIDEO = "0";
  private static final String DECODED_PICTURES = "0";
  // How far past the latest picture's time the first picture check reads, so that the decoder
  // receives the packets that the pictures before that time reference, which B-frames place up to
  // a few frames later.
  private static final Duration REFERENCES_PAST_THE_LATEST_PICTURE = Duration.ofSeconds(1);
  // A microsecond covers the rounding of the server's video end and of a check's times to whole
  // microseconds or nanoseconds.
  private static final Duration TIME_ROUNDING = Duration.ofNanos(1_000);
  private static final int QUIT = 'q';
  private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

  @NonNull private final FfmpegCommandBuilder commandBuilder;
  @NonNull private final ProcessLauncher launcher;
  @NonNull private final TranscodeRequest request;
  @NonNull private final LongConsumer bytesRead;

  /** A keyframe on the attempt's zero-based timeline. */
  record Keyframe(Duration presentationTime, Duration decodeTime) {}

  /** What a scan of an interval read of the chosen video stream. */
  record IntervalScan(List<Keyframe> keyframes, Optional<Duration> lastDecodeTime) {}

  /**
   * A read of the source from a seek position, and on the attempt's zero-based timeline the latest
   * decode time of the first video packet that the read may start with and the latest presentation
   * time of a first picture in time.
   */
  @Builder
  record ReadCheck(
      @NonNull Duration seekPosition,
      @NonNull Duration latestLanding,
      @NonNull Duration latestPicture) {}

  /** What the attempt's own read from a seek position shows first. */
  sealed interface SeekRead {

    /** The seek reads no video packet, as a raw H.264 stream's demuxer, which cannot seek, does. */
    record NoVideo() implements SeekRead {}

    /**
     * The decoder shows no picture in time: the read's first video packet decodes after the latest
     * landing, or the decoder shows no picture from the read up to a second past the latest
     * picture's time.
     */
    record NoPictureInTime() implements SeekRead {}

    /** The decoder's first picture, which can present after the latest picture's time. */
    record FirstPicture(Duration presentationTime) implements SeekRead {}
  }

  /**
   * What the attempt's own read from the check's seek position shows first. One FFmpeg copies the
   * read's first video packet, which ends a read that starts after the latest landing at once.
   * Another then decodes the read as {@link #firstDecodedPicture} does.
   */
  SeekRead firstPicture(ReadCheck check) throws InterruptedException {
    var landing =
        run(
            commandBuilder.buildSeekLandingCommand(
                request.sourcePath(), request.transcodeDecision(), check.seekPosition()),
            this::readFirstVideoDecodeTime);
    if (landing.isEmpty()) {
      return new SeekRead.NoVideo();
    }

    if (landing.get().compareTo(check.latestLanding()) > 0) {
      return new SeekRead.NoPictureInTime();
    }

    return firstDecodedPicture(check.seekPosition(), check.latestPicture());
  }

  /**
   * The decoder's first picture from the attempt's own read from the seek position, without a check
   * of where the read lands. One FFmpeg decodes the chosen video from where that seek lands, as the
   * attempt's decoder does, and copies the packets that it reads to a second output, whose bytes
   * count as read. Once a copied video packet decodes at or after a second past the latest
   * picture's time, FFmpeg stops reading and decodes the packets that it has read. When the request
   * carries the chosen video's end, the packet that ends there also stops the read, unless the read
   * holds keyframes and every one of them presents after the latest picture's time: the leading
   * pictures after them can then still present by that time. Any other read whose video ends first
   * and that shows no picture copies on to the end of the file.
   */
  SeekRead firstDecodedPicture(Duration seekPosition, Duration latestPicture)
      throws InterruptedException {
    if (readEnd(latestPicture).compareTo(seekPosition) <= 0) {
      return new SeekRead.NoPictureInTime();
    }

    var process =
        launch(
            commandBuilder.buildFirstPictureCommand(
                request.sourcePath(), request.transcodeDecision(), seekPosition));
    var copies = new FutureTask<Void>(() -> readCopies(latestPicture, process), null);
    Thread.ofVirtual().name("first-picture-copies-" + request.attemptId()).start(copies);
    try {
      return read(process, this::readFirstPicture)
          .<SeekRead>map(SeekRead.FirstPicture::new)
          .orElseGet(SeekRead.NoPictureInTime::new);
    } finally {
      process.destroyForcibly();
      awaitCopies(copies);
    }
  }

  /**
   * Every keyframe that decodes in the interval, in decode order, from zero every keyframe that
   * decodes before the interval's end, and the latest decode time of any video packet that the scan
   * read. FFmpeg's seek into an MPEG-TS source lands on decode times and its duration ends at one,
   * so a keyframe that presents in one interval and decodes in the interval before it reaches only
   * the earlier interval's scan.
   */
  IntervalScan scan(Duration from, Duration until) throws InterruptedException {
    var interval = new Interval(from, until);
    var packets = run(scanCommand(interval), this::readScannedVideo);
    return new IntervalScan(
        packets.stream()
            .filter(packet -> packet.isKeyframe() && interval.decodes(packet))
            .map(Packet::times)
            .toList(),
        packets.stream().map(Packet::decodeTime).max(Comparator.naturalOrder()));
  }

  private List<String> scanCommand(Interval interval) {
    return commandBuilder.buildKeyframeScanCommand(
        request.sourcePath(),
        request.transcodeDecision(),
        interval.from(),
        interval.until().minus(interval.from()));
  }

  private <T> T run(List<String> command, OutputReader<T> reader) throws InterruptedException {
    var process = launch(command);
    try {
      return read(process, reader);
    } finally {
      process.destroyForcibly();
    }
  }

  private Process launch(List<String> command) {
    try {
      return launcher.launch(command, request.attemptId());
    } catch (IOException e) {
      throw new TranscodeException("FFmpeg could not start a keyframe scan", e);
    }
  }

  // The read runs on a thread of its own, so an interrupt ends the wait at once; the caller's
  // forcible end of FFmpeg then ends the read, also once the reader has read what it needs.
  private <T> T read(Process process, OutputReader<T> reader) throws InterruptedException {
    var reading = new FutureTask<>(() -> reader.read(process));
    Thread.ofVirtual().name("keyframe-scan-" + request.attemptId()).start(reading);
    try {
      return reading.get();
    } catch (ExecutionException e) {
      throw new TranscodeException("A keyframe scan failed", e.getCause());
    }
  }

  @FunctionalInterface
  private interface OutputReader<T> {
    T read(Process process) throws IOException, InterruptedException;
  }

  private List<Packet> readScannedVideo(Process process) throws IOException, InterruptedException {
    var packets = new ArrayList<Packet>();
    var framecrc = new Framecrc(Optional.empty());
    try (var output = outputOf(process)) {
      for (var line = output.readLine(); line != null; line = output.readLine()) {
        framecrc
            .packetOf(line)
            .filter(packet -> COPIED_VIDEO.equals(packet.stream()))
            .ifPresent(packets::add);
      }
    }

    requireSuccess(process);
    return packets;
  }

  private Optional<Duration> readFirstVideoDecodeTime(Process process)
      throws IOException, InterruptedException {
    return readFirst(process, new Framecrc(Optional.empty()), COPIED_VIDEO).map(Packet::decodeTime);
  }

  private Optional<Duration> readFirstPicture(Process process)
      throws IOException, InterruptedException {
    return readFirst(process, new Framecrc(Optional.of(DECODED_PICTURES)), DECODED_PICTURES)
        .map(Packet::presentationTime);
  }

  // FFmpeg writes a first picture check's copies to its error output as it reads them, while the
  // decoder's first picture waits for the packets that it references. A q makes FFmpeg stop reading
  // and decode the packets that it has read; the forcible end of FFmpeg closes the error output.
  // A reader that fails ends FFmpeg, which would otherwise wait for the reader to take its error
  // output.
  private void readCopies(Duration latestPicture, Process process) {
    var stopped = false;
    var framecrc = new Framecrc(Optional.empty());
    var copiedVideo = new CopiedVideo(latestPicture);
    try (var copies = linesOf(process.getErrorStream())) {
      for (var line = copies.readLine(); line != null; line = copies.readLine()) {
        framecrc
            .packetOf(line)
            .filter(packet -> COPIED_VIDEO.equals(packet.stream()))
            .ifPresent(copiedVideo::add);
        if (!stopped && copiedVideo.reachesTheReadEnd()) {
          stopped = true;
          quit(process);
        }
      }
    } catch (IOException _) {
      // The forcible end of FFmpeg closed its error output.
    } catch (RuntimeException e) {
      process.destroyForcibly();
      throw e;
    }
  }

  private static Duration readEnd(Duration latestPicture) {
    return latestPicture.plus(REFERENCES_PAST_THE_LATEST_PICTURE);
  }

  private static void awaitCopies(FutureTask<Void> copies) throws InterruptedException {
    try {
      copies.get();
    } catch (ExecutionException e) {
      throw new TranscodeException(
          "A first picture check's copies could not be read", e.getCause());
    }
  }

  private static void quit(Process process) {
    try {
      var input = process.getOutputStream();
      input.write(QUIT);
      input.flush();
    } catch (IOException _) {
      // FFmpeg has exited.
    }
  }

  // The output's first packet of the stream; the forcible end of FFmpeg then ends the rest.
  private static Optional<Packet> readFirst(Process process, Framecrc framecrc, String stream)
      throws IOException, InterruptedException {
    try (var output = outputOf(process)) {
      for (var line = output.readLine(); line != null; line = output.readLine()) {
        var packet = framecrc.packetOf(line).filter(read -> stream.equals(read.stream()));
        if (packet.isPresent()) {
          return packet;
        }
      }
    }

    requireSuccess(process);
    return Optional.empty();
  }

  private static BufferedReader outputOf(Process process) {
    return linesOf(process.getInputStream());
  }

  private static BufferedReader linesOf(InputStream output) {
    return new BufferedReader(new InputStreamReader(output, StandardCharsets.US_ASCII));
  }

  private static void requireSuccess(Process process) throws InterruptedException {
    var exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new TranscodeException("A keyframe scan's FFmpeg exited with " + exitCode);
    }
  }

  // The packet records of a framecrc, each in the time base that the framecrc names for its
  // stream. Every packet but the decoder's pictures is a source packet whose bytes the consumer
  // takes.
  private final class Framecrc {

    private final Optional<String> decodedStream;
    private final Map<String, TimeBase> timeBases = new HashMap<>();

    private Framecrc(Optional<String> decodedStream) {
      this.decodedStream = decodedStream;
    }

    Optional<Packet> packetOf(String line) {
      var timeBaseLine = TIME_BASE.matcher(line);
      if (timeBaseLine.matches()) {
        timeBases.put(
            timeBaseLine.group(1),
            new TimeBase(
                Long.parseLong(timeBaseLine.group(2)), Long.parseLong(timeBaseLine.group(3))));
        return Optional.empty();
      }

      var packetRecord = RECORD.matcher(line);
      if (!packetRecord.find()) {
        return Optional.empty();
      }

      var stream = packetRecord.group(1);
      if (decodedStream.filter(stream::equals).isEmpty()) {
        bytesRead.accept(Long.parseLong(packetRecord.group(5)));
      }

      return Optional.ofNullable(timeBases.get(stream))
          .flatMap(timeBase -> timedPacket(stream, packetRecord, timeBase));
    }

    // A packet without a presentation time counts at its decode time, as a raw H.264 stream's
    // does, and a packet without a positive duration ends where it presents.
    private static Optional<Packet> timedPacket(
        String stream, Matcher packetRecord, TimeBase timeBase) {
      var decodeTime = Long.parseLong(packetRecord.group(2));
      var presentationTime = Long.parseLong(packetRecord.group(3));
      var duration = Math.max(0, Long.parseLong(packetRecord.group(4)));
      if (presentationTime == NO_TIME) {
        presentationTime = decodeTime;
      }

      if (decodeTime == NO_TIME) {
        decodeTime = presentationTime;
      }

      if (presentationTime == NO_TIME) {
        return Optional.empty();
      }

      return Optional.of(
          Packet.builder().stream(stream)
              .presentationTime(durationOf(timeBase, presentationTime))
              .presentationEnd(durationOf(timeBase, Math.addExact(presentationTime, duration)))
              .tick(durationOf(timeBase, 1))
              .decodeTime(durationOf(timeBase, decodeTime))
              .isKeyframe(isKeyframe(packetRecord))
              .build());
    }
  }

  // A keyframe that its container marks to decode but not to present, as an edit list marks its
  // preroll, still starts decoding.
  private static boolean isKeyframe(Matcher packetRecord) {
    var flags =
        Optional.ofNullable(packetRecord.group(6))
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

  /**
   * A packet of one of a framecrc's streams, on the attempt's zero-based timeline. {@code
   * presentationEnd} is its presentation time plus its duration, and {@code tick} is how long one
   * tick of its time base lasts.
   */
  @Builder
  private record Packet(
      String stream,
      Duration presentationTime,
      Duration presentationEnd,
      Duration tick,
      Duration decodeTime,
      boolean isKeyframe) {

    Keyframe times() {
      return new Keyframe(presentationTime, decodeTime);
    }
  }

  private record Interval(Duration from, Duration until) {

    boolean decodes(Packet packet) {
      var time = packet.decodeTime();
      return (from.isZero() || time.compareTo(from) >= 0) && time.compareTo(until) < 0;
    }
  }

  /** The video packets that a first picture check copies, which decide where its read ends. */
  private final class CopiedVideo {

    private final Duration latestPicture;
    private boolean holdsAKeyframe;
    private boolean holdsAKeyframeInTime;
    private boolean reachesTheReadEnd;

    private CopiedVideo(Duration latestPicture) {
      this.latestPicture = latestPicture;
    }

    void add(Packet packet) {
      if (packet.isKeyframe()) {
        holdsAKeyframe = true;
        holdsAKeyframeInTime |= packet.presentationTime().compareTo(latestPicture) <= 0;
      }

      reachesTheReadEnd |= endsTheRead(packet);
    }

    boolean reachesTheReadEnd() {
      return reachesTheReadEnd;
    }

    private boolean endsTheRead(Packet packet) {
      if (packet.decodeTime().compareTo(readEnd(latestPicture)) >= 0) {
        return true;
      }

      return request.videoEnd().filter(videoEnd -> endsTheVideo(packet, videoEnd)).isPresent()
          && !canShowAPictureInTimeOnlyFromLaterPackets();
    }

    // The packet that ends the video presents last, so no keyframe follows it in decode order: a
    // read that holds no keyframe shows no picture, and one with a keyframe that presents by the
    // latest picture's time shows a picture by then from the packets that it holds. When every
    // keyframe presents later, the leading pictures that follow one in decode order can still
    // present by then. A copied packet's decode time cannot rule them out: where the container
    // stores none, as Matroska does, FFmpeg derives it from the presentation time.
    private boolean canShowAPictureInTimeOnlyFromLaterPackets() {
      return holdsAKeyframe && !holdsAKeyframeInTime;
    }

    // The server's video end subtracts the container's start time as ffprobe prints it, in whole
    // microseconds. FFmpeg's -start_at_zero subtracts that start rounded to the nearest of the
    // packet's ticks, so on the check's timeline the packet that ends the video can end up to half
    // a tick before the server's video end.
    private static boolean endsTheVideo(Packet packet, Duration videoEnd) {
      return packet
              .presentationEnd()
              .plus(packet.tick().dividedBy(2))
              .plus(TIME_ROUNDING)
              .compareTo(videoEnd)
          >= 0;
    }
  }
}
