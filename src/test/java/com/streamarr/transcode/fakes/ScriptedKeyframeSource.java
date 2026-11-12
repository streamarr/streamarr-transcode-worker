package com.streamarr.transcode.fakes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.Builder;

/**
 * An index-less source, standing in for FFmpeg's keyframe scans and read checks of it. Its video
 * has a picture every 250 ms up to its end, keyframes at the given presentation times, each decoded
 * 83 ms before it presents, and an audio packet every 250 ms. Each scan writes the framecrc of the
 * packets that decode inside the interval that its command's seek and duration name, as FFmpeg's
 * scan of an MPEG-TS source does: the seek lands on the packets' decode times, and the duration
 * ends at a decode time. A scan without a seek reads every packet that decodes before its end. A
 * source that seeks by presentation time, as an MP4 source does, lands on the last keyframe that
 * presents at or before the seek and reads the packets that present from the seek on. A read check
 * runs two FFmpegs. The landing check writes the first video packet from where its seek lands,
 * beside an audio packet. The first picture check writes, when the decoder shows a picture by the
 * end of its read, the video packets from where its seek lands, each beside an audio packet, up to
 * that picture, which it writes once a packet decodes at or after its presentation time; it writes
 * nothing when the decoder shows none, as FFmpeg's muxer waits for the picture. The decoder starts
 * at the first keyframe that the read reaches and shows its first picture there, or a recovery
 * delay after it.
 */
public final class ScriptedKeyframeSource {

  /** How long before it presents each picture decodes, as B-frames make it. */
  public static final Duration REORDER_DELAY = Duration.ofMillis(83);

  private static final int VIDEO_PACKET_BYTES = 1_000;
  private static final int AUDIO_PACKET_BYTES = 300;
  private static final long PACKET_MILLIS = 250;
  private static final String LANDING_CHECK_MARK = "-frames:v";
  private static final String FIRST_PICTURE_CHECK_MARK = "-frames:v:0";

  private final Settings settings;
  private final List<List<Duration>> scanned = new ArrayList<>();
  private final List<Duration> readChecks = new ArrayList<>();
  private final List<Duration> firstPictureReadEnds = new ArrayList<>();
  private long bytesWritten;

  public ScriptedKeyframeSource(List<Duration> keyframes, int videoEndSeconds) {
    this(
        Settings.builder()
            .keyframes(List.copyOf(keyframes))
            .videoEndSeconds(videoEndSeconds)
            .recoveryDelays(Map.of())
            .build());
  }

  private ScriptedKeyframeSource(Settings settings) {
    this.settings = settings;
  }

  /** A source whose seeks land on presentation times, as an MP4 source's do. */
  public static ScriptedKeyframeSource seekingByPresentationTime(
      List<Duration> keyframes, int videoEndSeconds) {
    return new ScriptedKeyframeSource(
        new ScriptedKeyframeSource(keyframes, videoEndSeconds)
            .settings.toBuilder().seeksByPresentationTime(true).build());
  }

  /** Keyframes every 2 s from 0 s up to and including the last. */
  public static List<Duration> keyframesEveryTwoSecondsUpTo(int lastKeyframeSeconds) {
    return IntStream.rangeClosed(0, lastKeyframeSeconds / 2)
        .mapToObj(index -> Duration.ofSeconds(index * 2L))
        .toList();
  }

  /** Whether the command is one of a read check's FFmpegs. */
  public static boolean isReadCheck(List<String> command) {
    return isLandingCheck(command) || isFirstPictureCheck(command);
  }

  /** Whether the command is a read check's landing check, which copies the first video packet. */
  public static boolean isLandingCheck(List<String> command) {
    return command.contains(LANDING_CHECK_MARK);
  }

  /** Whether the command is a read check's first picture check, which decodes the read. */
  public static boolean isFirstPictureCheck(List<String> command) {
    return command.contains(FIRST_PICTURE_CHECK_MARK);
  }

  /**
   * A read check's FFmpeg for a source that holds a keyframe at every seek position: the read
   * starts with that keyframe, and the decoder shows its picture.
   */
  public static ScriptedProcess keyframeAtTheSeekOf(List<String> command) {
    var micros = argumentAfter(command, "-ss").orElse(Duration.ZERO).toNanos() / 1_000;
    var framecrc = "#tb 0: 1/1000000\n0, %d, %d, 1, 1000, 0x0a1b2c3d\n".formatted(micros, micros);
    if (isFirstPictureCheck(command)) {
      framecrc =
          """
          #tb 0: 1/1000000
          #tb 1: 1/1000000
          1, %d, %d, 1, 1000, 0x0a1b2c3d
          0, %d, %d, 1, 500, 0x0a1b2c3d
          """
              .formatted(micros, micros, micros, micros);
    }

    return ScriptedProcess.builder().output(framecrc.getBytes(StandardCharsets.US_ASCII)).build();
  }

  /**
   * This source, whose seek to a packet's exact decode time reads from the packet after it, as an
   * MPEG-TS seek does when FFmpeg's probe saw none of the stream's B-frames.
   */
  public ScriptedKeyframeSource landingAfterAnExactDecodeTime() {
    return new ScriptedKeyframeSource(
        settings.toBuilder().landsAfterAnExactDecodeTime(true).build());
  }

  /**
   * This source, whose decoder, starting at the keyframe, shows its first picture the delay after
   * it, as a decoder that starts at a recovery point does.
   */
  public ScriptedKeyframeSource recoveringFrom(Duration keyframe, Duration delay) {
    var delays = new HashMap<>(settings.recoveryDelays());
    delays.put(keyframe, delay);
    return new ScriptedKeyframeSource(
        settings.toBuilder().recoveryDelays(Map.copyOf(delays)).build());
  }

  /** The scan's or read check's framecrc that the command names, as a process that writes it. */
  public ScriptedProcess scan(List<String> command) {
    return ScriptedProcess.builder().output(framecrcOf(command)).build();
  }

  /** The framecrc that a scan or read check with the command writes. */
  public synchronized byte[] framecrcOf(List<String> command) {
    var seek = argumentAfter(command, "-ss");
    if (isLandingCheck(command)) {
      return landingCheck(seek.orElseThrow());
    }

    if (isFirstPictureCheck(command)) {
      var from = seek.orElse(Duration.ZERO);
      return firstPictureCheck(from, from.plus(argumentAfter(command, "-t").orElseThrow()));
    }

    var from = seek.orElse(Duration.ZERO);
    var until = from.plus(argumentAfter(command, "-t").orElseThrow());
    scanned.add(List.of(from, until));
    var framecrc = new StringBuilder("#tb 0: 1/1000\n#tb 1: 1/1000\n");
    pictures().stream()
        .filter(time -> isRead(time, seek, until))
        .forEach(time -> framecrc.append(videoRecord("0", time)));
    for (var millis = from.toMillis(); millis < until.toMillis(); millis += PACKET_MILLIS) {
      framecrc.append(audioRecord("1", millis));
    }

    return framecrc.toString().getBytes(StandardCharsets.US_ASCII);
  }

  /** The seek of each read check, in check order. */
  public synchronized List<Duration> readChecks() {
    return List.copyOf(readChecks);
  }

  /** Where each first picture check stops reading, in check order. */
  public synchronized List<Duration> firstPictureReadEnds() {
    return List.copyOf(firstPictureReadEnds);
  }

  /** Each scanned interval's start and end, in scan order. */
  public synchronized List<List<Duration>> scannedIntervals() {
    return List.copyOf(scanned);
  }

  /** The bytes of every source packet that the scans and read checks have written. */
  public synchronized long bytesWritten() {
    return bytesWritten;
  }

  private byte[] landingCheck(Duration seek) {
    readChecks.add(seek);
    var framecrc = new StringBuilder("#tb 0: 1/1000\n#tb 1: 1/1000\n");
    picturesReadFrom(seek).stream()
        .findFirst()
        .ifPresent(
            time -> {
              framecrc.append(audioRecord("1", time.minus(REORDER_DELAY).toMillis()));
              framecrc.append(videoRecord("0", time));
            });
    return framecrc.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private byte[] firstPictureCheck(Duration seek, Duration readEnd) {
    firstPictureReadEnds.add(readEnd);
    var read = picturesReadFrom(seek);
    var firstPicture =
        read.stream()
            .filter(settings.keyframes()::contains)
            .findFirst()
            .flatMap(this::firstPictureFrom)
            .filter(picture -> picture.compareTo(readEnd) <= 0);
    if (firstPicture.isEmpty()) {
      return new byte[0];
    }

    var framecrc = new StringBuilder("#tb 0: 1/1000\n#tb 1: 1/1000\n#tb 2: 1/1000\n");
    for (var time : read) {
      framecrc.append(videoRecord("1", time));
      framecrc.append(audioRecord("2", time.minus(REORDER_DELAY).toMillis()));
      if (isDecodedBy(time, firstPicture.get())) {
        break;
      }
    }

    framecrc.append(pictureRecord(firstPicture.get()));
    return framecrc.toString().getBytes(StandardCharsets.US_ASCII);
  }

  // The packet decodes at or after the picture's presentation time.
  private static boolean isDecodedBy(Duration packet, Duration picture) {
    return packet.minus(REORDER_DELAY).compareTo(picture) >= 0;
  }

  private static String pictureRecord(Duration presentationTime) {
    var millis = presentationTime.toMillis();
    return "0, %d, %d, 1, 500, 0x0a1b2c3d\n".formatted(millis, millis);
  }

  // The pictures whose packets the read from the seek reads, in decode order.
  private List<Duration> picturesReadFrom(Duration seek) {
    if (settings.seeksByPresentationTime()) {
      var keyframe =
          settings.keyframes().stream()
              .filter(time -> time.compareTo(seek) <= 0)
              .max(Duration::compareTo);
      return keyframe
          .map(start -> pictures().stream().filter(time -> time.compareTo(start) >= 0).toList())
          .orElse(List.of());
    }

    return pictures().stream().filter(time -> isLandedOn(time.minus(REORDER_DELAY), seek)).toList();
  }

  private boolean isLandedOn(Duration decodeTime, Duration seek) {
    var comparison = decodeTime.compareTo(seek);
    if (settings.landsAfterAnExactDecodeTime()) {
      return comparison > 0;
    }

    return comparison >= 0;
  }

  // The decoder starts at the keyframe and shows the first picture that presents at or after its
  // recovery delay.
  private Optional<Duration> firstPictureFrom(Duration keyframe) {
    var shown = keyframe.plus(settings.recoveryDelays().getOrDefault(keyframe, Duration.ZERO));
    return pictures().stream().filter(time -> time.compareTo(shown) >= 0).findFirst();
  }

  private String videoRecord(String stream, Duration presentationTime) {
    bytesWritten += VIDEO_PACKET_BYTES;
    var flags = settings.keyframes().contains(presentationTime) ? "" : ", F=0x0";
    return "%s, %d, %d, %d, %d, 0x0a1b2c3d%s"
            .formatted(
                stream,
                presentationTime.minus(REORDER_DELAY).toMillis(),
                presentationTime.toMillis(),
                PACKET_MILLIS,
                VIDEO_PACKET_BYTES,
                flags)
        + "\n";
  }

  private String audioRecord(String stream, long millis) {
    bytesWritten += AUDIO_PACKET_BYTES;
    return "%s, %d, %d, %d, %d, 0x0a1b2c3d"
            .formatted(stream, millis, millis, PACKET_MILLIS, AUDIO_PACKET_BYTES)
        + "\n";
  }

  private List<Duration> pictures() {
    var pictures = new ArrayList<>(settings.keyframes());
    for (var millis = 0L; millis < settings.videoEndSeconds() * 1_000L; millis += PACKET_MILLIS) {
      pictures.add(Duration.ofMillis(millis));
    }

    return pictures.stream().distinct().sorted().toList();
  }

  private boolean isRead(Duration presentationTime, Optional<Duration> seek, Duration until) {
    var decodeTime = presentationTime.minus(REORDER_DELAY);
    if (decodeTime.compareTo(until) >= 0) {
      return false;
    }

    if (seek.isEmpty()) {
      return true;
    }

    var readFrom = settings.seeksByPresentationTime() ? presentationTime : decodeTime;
    return readFrom.compareTo(seek.get()) >= 0;
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

  @Builder(toBuilder = true)
  private record Settings(
      List<Duration> keyframes,
      int videoEndSeconds,
      boolean seeksByPresentationTime,
      boolean landsAfterAnExactDecodeTime,
      Map<Duration, Duration> recoveryDelays) {}
}
