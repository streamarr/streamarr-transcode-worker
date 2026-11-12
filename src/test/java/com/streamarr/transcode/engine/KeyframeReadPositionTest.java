package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.streamarr.transcode.fakes.ScriptedKeyframeSource;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The search for the keyframe that an encode from a later segment reads from, against an index-less
 * source whose seeks land on decode times unless it says otherwise (ADR 0037, seek).
 */
@Tag("UnitTest")
@DisplayName("Keyframe Read Position Tests")
class KeyframeReadPositionTest {

  private static final Duration ONE_MICROSECOND = Duration.ofNanos(1_000);
  // FFmpeg's command line tool moves a seek this much, 3/23 s, earlier for a stream with B-frames.
  private static final Duration B_FRAME_READ_BACK = Duration.ofNanos(130_434_783);

  private final AtomicLong bytesRead = new AtomicLong();

  // On the 2 s grid, segment 12 starts at 24 s and the search reads back from 23.5 s.
  @Test
  @DisplayName(
      "Should read from the decode time of the last keyframe at or before the search time when a"
          + " seek to its presentation time lands after it")
  void shouldReadFromTheDecodeTimeOfTheLastKeyframeWhenASeekToItsPresentationTimeLandsAfterIt()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30);

    var readPosition =
        engineReading(source).readPosition(searching(seconds("23.5"), 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(22));
    assertThat(source.scannedIntervals())
        .containsExactly(List.of(seconds("21.5"), seconds("23.5").plus(ONE_MICROSECOND)));
    assertThat(source.readChecks()).containsExactly(Duration.ofSeconds(22), decodeTimeOf(22));
  }

  @Test
  @DisplayName(
      "Should read from the keyframe's presentation time when a seek there lands on the keyframe,"
          + " as an MP4 source's seek does")
  void shouldReadFromTheKeyframesPresentationTimeWhenASeekThereLandsOnTheKeyframe()
      throws Exception {
    var source =
        ScriptedKeyframeSource.seekingByPresentationTime(
            ScriptedKeyframeSource.keyframesEveryTwoSecondsUpTo(30), 30);

    var readPosition =
        engineReading(source).readPosition(searching(seconds("23.5"), 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ofSeconds(22));
    assertThat(source.readChecks()).containsExactly(Duration.ofSeconds(22));
  }

  // Keyframes 49 s apart: the first interval is the 2 s period, and each one before it is twice as
  // long as the one after it.
  @Test
  @DisplayName(
      "Should scan intervals each twice as long as the one after it when they run back from the"
          + " search time until one holds a keyframe that presents early enough")
  void shouldScanIntervalsEachTwiceAsLongAsTheOneAfterItWhenRunningBackFromTheSearchTime()
      throws Exception {
    var source = new ScriptedKeyframeSource(List.of(Duration.ZERO, Duration.ofSeconds(1)), 50);

    var readPosition =
        engineReading(source).readPosition(searching(seconds("49.5"), 50), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(1));
    assertThat(source.scannedIntervals())
        .containsExactly(
            List.of(seconds("47.5"), seconds("49.5").plus(ONE_MICROSECOND)),
            List.of(seconds("43.5"), seconds("47.5")),
            List.of(seconds("35.5"), seconds("43.5")),
            List.of(seconds("19.5"), seconds("35.5")),
            List.of(Duration.ZERO, seconds("19.5")));
  }

  // The keyframe at 22 s decodes at 21.917 s, before the first interval starts at 21.95 s.
  @Test
  @DisplayName(
      "Should find the keyframe in the interval where it decodes when it presents in the interval"
          + " after it")
  void shouldFindTheKeyframeInTheIntervalWhereItDecodesWhenItPresentsInTheIntervalAfterIt()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30);

    var readPosition =
        engineReading(source).readPosition(searching(seconds("23.95"), 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(22));
    assertThat(source.scannedIntervals()).hasSize(2);
  }

  @Test
  @DisplayName(
      "Should read from the source's start without a read check when no keyframe presents at or"
          + " before the search time")
  void shouldReadFromTheSourcesStartWhenNoKeyframePresentsAtOrBeforeTheSearchTime()
      throws Exception {
    var source = new ScriptedKeyframeSource(List.of(seconds("10"), seconds("20")), 30);

    var readPosition =
        engineReading(source).readPosition(searching(seconds("5.5"), 6), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
    assertThat(source.readChecks()).isEmpty();
    assertThat(source.scannedIntervals().getLast().getFirst()).isZero();
  }

  // The video's keyframes stop at 10 s and its pictures at 20 s, before segment 12's start at 24 s.
  // The first scan reads to 25 s and meets no picture at or after 24 s.
  @Test
  @DisplayName(
      "Should read from the last keyframe at least 0.5 s before the video's last keyframe when a held"
          + " encode's video ends before its first segment")
  void shouldReadFromTheKeyframeHalfASecondBeforeTheLastWhenAHeldEncodesVideoEndsBeforeItsStart()
      throws Exception {
    var source = everyTwoSecondsUpTo(10, 20);

    var readPosition =
        engineReading(source)
            .readPosition(holding(searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(8));
    assertThat(source.scannedIntervals())
        .containsExactly(
            List.of(seconds("21.5"), Duration.ofSeconds(25)),
            List.of(seconds("17.5"), seconds("21.5")),
            List.of(seconds("9.5"), seconds("17.5")),
            List.of(Duration.ZERO, seconds("9.5")));
  }

  @Test
  @DisplayName(
      "Should read from the last keyframe at or before the search time when a held encode's video"
          + " goes on past its first segment's start")
  void shouldReadFromTheLastKeyframeBeforeTheSearchTimeWhenAHeldEncodesVideoGoesOnPastItsStart()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30);

    var readPosition =
        engineReading(source)
            .readPosition(holding(searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(22));
    assertThat(source.scannedIntervals())
        .containsExactly(List.of(seconds("21.5"), Duration.ofSeconds(25)));
  }

  @Test
  @DisplayName(
      "Should read from the source's start when no keyframe lies 0.5 s before the last keyframe of a"
          + " held encode's video")
  void shouldReadFromTheSourcesStartWhenNoKeyframeLiesHalfASecondBeforeTheHeldVideosLast()
      throws Exception {
    var source = new ScriptedKeyframeSource(List.of(seconds("0.3"), seconds("0.6")), 20);

    var readPosition =
        engineReading(source)
            .readPosition(holding(searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
  }

  // A raw H.264 stream's demuxer cannot seek, so a seek reads no packet at all.
  @Test
  @DisplayName(
      "Should read from the source's start when no seek to the keyframe's times reads a video"
          + " packet")
  void shouldReadFromTheSourcesStartWhenNoSeekToTheKeyframesTimesReadsAVideoPacket()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30);
    var engine =
        engine(
            ScriptedProcessLauncher.forCommands(
                (command, _) ->
                    ScriptedKeyframeSource.isReadCheck(command)
                        ? ScriptedProcess.builder().output(new byte[0]).build()
                        : source.scan(command)));

    var readPosition = engine.readPosition(searching(seconds("23.5"), 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
  }

  // The source's first keyframe decodes 83 ms before it presents at 0 s.
  @Test
  @DisplayName(
      "Should read from the source's start when the keyframe that the search finds decodes before"
          + " it")
  void shouldReadFromTheSourcesStartWhenTheFoundKeyframeDecodesBeforeIt() throws Exception {
    var source = new ScriptedKeyframeSource(List.of(Duration.ZERO, Duration.ofSeconds(10)), 20);

    var readPosition =
        engineReading(source).readPosition(searching(seconds("7.5"), 8), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
  }

  // A raw H.264 stream's packets carry decode times alone. The record before the time base line
  // is not read. The read from 10 s starts with the keyframe and shows its picture.
  @Test
  @DisplayName(
      "Should place a keyframe at its decode time when its packet carries no presentation time")
  void shouldPlaceAKeyframeAtItsDecodeTimeWhenItsPacketCarriesNoPresentationTime()
      throws Exception {
    var scan =
        """
        0, 9000, 9000, 250, 1000, 0x0a1b2c3d
        #tb 0: 1/1000
        0, 8000, -9223372036854775808, 250, 1000, 0x0a1b2c3d
        0, 10000, -9223372036854775808, 250, 1000, 0x0a1b2c3d
        0, -9223372036854775808, -9223372036854775808, 250, 1000, 0x0a1b2c3d
        """;
    var landing =
        """
        #tb 0: 1/1000
        0, 10000, -9223372036854775808, 250, 1000, 0x0a1b2c3d
        """;
    var firstPicture =
        """
        #tb 0: 1/1000
        #tb 1: 1/1000
        1, 10000, -9223372036854775808, 250, 1000, 0x0a1b2c3d
        0, 10000, 10000, 1, 500, 0x0a1b2c3d
        """;
    var engine =
        engine(
            ScriptedProcessLauncher.forCommands(
                (command, _) -> {
                  var output = scan;
                  if (ScriptedKeyframeSource.isLandingCheck(command)) {
                    output = landing;
                  }

                  if (ScriptedKeyframeSource.isFirstPictureCheck(command)) {
                    output = firstPicture;
                  }

                  return ScriptedProcess.builder()
                      .output(output.getBytes(StandardCharsets.US_ASCII))
                      .build();
                }));

    var readPosition = engine.readPosition(searching(seconds("23.5"), 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ofSeconds(10));
  }

  // An MP4 clip that its edit list trims inside a GOP marks its keyframe, and every picture before
  // the clip's start, to decode but not to present.
  @Test
  @DisplayName(
      "Should read from the source's start when the video's only keyframe decodes but is not"
          + " presented")
  void shouldReadFromTheSourcesStartWhenTheOnlyKeyframeDecodesButIsNotPresented() throws Exception {
    var framecrc =
        """
        #tb 0: 1/1000
        0, -1083, -1000, 42, 2590, 0x0a1b2c3d, F=0x5
        0, -1042, -875, 42, 918, 0x0a1b2c3d, F=0x4
        0, 0, 42, 42, 418, 0x0a1b2c3d, F=0x0
        """;
    var engine =
        engine(
            ScriptedProcessLauncher.forCommands(
                (command, _) ->
                    ScriptedProcess.builder()
                        .output(
                            command.contains("-ss")
                                ? new byte[0]
                                : framecrc.getBytes(StandardCharsets.US_ASCII))
                        .build()));

    var readPosition = engine.readPosition(searching(seconds("7.5"), 8), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
  }

  // The server stores the keyframe at 22 s and sends its decode time.
  @Test
  @DisplayName(
      "Should keep the stored seek position after one read check, without a scan, when its read"
          + " shows the keyframe's picture at or before the search time")
  void shouldKeepTheStoredSeekPositionWhenItsReadShowsThePictureAtOrBeforeTheSearchTime()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30);

    var readPosition =
        engineReading(source)
            .readPosition(
                storing(decodeTimeOf(22), searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(22));
    assertThat(source.readChecks()).containsExactly(decodeTimeOf(22));
    assertThat(source.scannedIntervals()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should read from the source's start without a read check when the stored seek position is"
          + " zero")
  void shouldReadFromTheSourcesStartWithoutAReadCheckWhenTheStoredSeekPositionIsZero()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30);

    var readPosition =
        engineReading(source)
            .readPosition(
                storing(Duration.ZERO, searching(seconds("5.5"), 6)), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
    assertThat(source.readChecks()).isEmpty();
    assertThat(source.scannedIntervals()).isEmpty();
  }

  // A seek to the keyframe's exact decode time, as to its presentation time, reads from the packet
  // after it, so the decoder shows no picture until the keyframe at 24 s. A seek FFmpeg's B-frame
  // read-back before the decode time reads the keyframe.
  @Test
  @DisplayName(
      "Should read from FFmpeg's B-frame read-back before the keyframe's decode time when a seek to"
          + " the stored decode time lands after the keyframe")
  void shouldReadFromTheBFrameReadBackBeforeTheDecodeTimeWhenTheStoredSeekLandsAfterTheKeyframe()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30).landingAfterAnExactDecodeTime();

    var readPosition =
        engineReading(source)
            .readPosition(
                storing(decodeTimeOf(22), searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(22).minus(B_FRAME_READ_BACK));
    // The seek's argument rounds the read-back's time up to whole microseconds.
    assertThat(source.readChecks())
        .containsExactly(
            decodeTimeOf(22), Duration.ofSeconds(22), decodeTimeOf(22), seconds("21.786566"));
  }

  // From the recovery point at 22 s the decoder shows its first picture 1.75 s later, at 23.75 s,
  // after the search time. From the keyframe at 20 s it would show it at 21.75 s.
  @Test
  @DisplayName(
      "Should read from the keyframe before when the decoder shows its first picture from the stored"
          + " keyframe after the search time, as from a recovery point")
  void shouldReadFromTheKeyframeBeforeWhenTheDecodersFirstPictureFromTheStoredKeyframeIsLate()
      throws Exception {
    var source =
        everyTwoSecondsUpTo(30, 30).recoveringFrom(Duration.ofSeconds(22), seconds("1.75"));

    var readPosition =
        engineReading(source)
            .readPosition(
                storing(decodeTimeOf(22), searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(20));
    assertThat(source.readChecks())
        .containsExactly(
            decodeTimeOf(22),
            Duration.ofSeconds(22),
            decodeTimeOf(22),
            Duration.ofSeconds(20),
            decodeTimeOf(20));
  }

  // From the recovery point at 22 s the decoder shows its first picture at 27 s, 5 s later, after
  // the second past the search time, 24.5 s, at which each read of it stops.
  @Test
  @DisplayName(
      "Should read from the keyframe before when the decoder shows no picture from the last keyframe"
          + " by a second past the search time")
  void shouldReadFromTheKeyframeBeforeWhenTheDecoderShowsNoPictureByASecondPastTheSearchTime()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30).recoveringFrom(Duration.ofSeconds(22), seconds("5"));

    var readPosition =
        engineReading(source).readPosition(searching(seconds("23.5"), 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(20));
    // Each seek and length round up to whole microseconds.
    assertThat(source.firstPictureReadEnds())
        .isNotEmpty()
        .allSatisfy(end -> assertThat(end).isBetween(seconds("24.5"), seconds("24.500002")));
  }

  // No keyframe's read shows a picture before the video ends at 30 s.
  @Test
  @DisplayName(
      "Should read from the source's start after checking four keyframes when the decoder shows no"
          + " picture from any of them")
  void shouldReadFromTheSourcesStartAfterCheckingFourKeyframesWhenTheDecoderShowsNoPicture()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30);
    for (var keyframe : ScriptedKeyframeSource.keyframesEveryTwoSecondsUpTo(30)) {
      source = source.recoveringFrom(keyframe, Duration.ofSeconds(60));
    }

    var readPosition =
        engineReading(source).readPosition(searching(seconds("23.5"), 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
    assertThat(source.readChecks())
        .contains(decodeTimeOf(16))
        .doesNotContain(Duration.ofSeconds(14), decodeTimeOf(14));
  }

  @Test
  @DisplayName("Should run no scan when the attempt carries no keyframe search time")
  void shouldRunNoScanWhenTheAttemptCarriesNoKeyframeSearchTime() throws Exception {
    var source = everyTwoSecondsUpTo(30, 30);
    var request =
        searching(seconds("23.5"), 24).toBuilder()
            .readFromLastKeyframePresentingAtOrBefore(Optional.empty())
            .seekPosition(Optional.of(Duration.ofSeconds(22)))
            .build();

    assertThat(engineReading(source).readPosition(request, bytesRead::addAndGet)).isEmpty();
    assertThat(source.scannedIntervals()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should count the bytes of every packet that each scan read, video and audio, as the search's"
          + " bytes read when the search scans")
  void shouldCountTheBytesOfEveryPacketThatEachScanReadWhenTheSearchScans() throws Exception {
    var source = everyTwoSecondsUpTo(10, 20);

    engineReading(source)
        .readPosition(holding(searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(bytesRead.get()).isEqualTo(source.bytesWritten()).isPositive();
  }

  @Test
  @DisplayName("Should fail the search when a scan's FFmpeg cannot start")
  void shouldFailTheSearchWhenAScansFfmpegCannotStart() {
    var engine =
        engine(
            (_, _) -> {
              throw new IOException("ffmpeg: not found");
            });
    var request = searching(seconds("23.5"), 24);

    assertThatExceptionOfType(TranscodeException.class)
        .isThrownBy(() -> engine.readPosition(request, bytesRead::addAndGet))
        .withCauseInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("Should fail the search when a scan's FFmpeg exits with an error")
  void shouldFailTheSearchWhenAScansFfmpegExitsWithAnError() {
    var engine =
        engine(
            ScriptedProcessLauncher.forCommands(
                (_, _) -> ScriptedProcess.builder().output(new byte[0]).exitCode(1).build()));
    var request = searching(seconds("23.5"), 24);

    assertThatExceptionOfType(TranscodeException.class)
        .isThrownBy(() -> engine.readPosition(request, bytesRead::addAndGet));
  }

  @ParameterizedTest(name = "{0} s")
  @ValueSource(ints = {0, -1})
  @DisplayName("Should refuse a search when its first interval is not positive")
  void shouldRefuseASearchWhenItsFirstIntervalIsNotPositive(int seconds) {
    var target =
        LastKeyframeSearch.Target.builder()
            .searchTime(Duration.ofSeconds(5))
            .firstSegmentStart(Duration.ofSeconds(6))
            .firstInterval(Duration.ofSeconds(seconds))
            .storedSeekPosition(Optional.empty());

    assertThatIllegalArgumentException().isThrownBy(target::build);
  }

  private FfmpegTranscodeEngine engineReading(ScriptedKeyframeSource source) {
    return engine(ScriptedProcessLauncher.forCommands((command, _) -> source.scan(command)));
  }

  private static FfmpegTranscodeEngine engine(ProcessLauncher launcher) {
    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
        .capabilityService(
            new TranscodeCapabilityService("ffmpeg", _ -> new FakeProcess("ffmpeg version 8.1", 0)))
        .launcher(launcher)
        .encoderStallTimeout(Duration.ofSeconds(30))
        .build();
  }

  private static ScriptedKeyframeSource everyTwoSecondsUpTo(
      int lastKeyframeSeconds, int videoEndSeconds) {
    return new ScriptedKeyframeSource(
        ScriptedKeyframeSource.keyframesEveryTwoSecondsUpTo(lastKeyframeSeconds), videoEndSeconds);
  }

  // An encode on a 2 s grid whose first segment starts at that many seconds and that searches for
  // the last keyframe that presents at or before the search time.
  private static TranscodeRequest searching(Duration searchTime, int firstSegmentStartSeconds) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .attemptId(UUID.randomUUID())
        .sourcePath(Path.of("/media/source.ts"))
        .targetSegmentDuration(2)
        .startSequenceNumber(firstSegmentStartSeconds / 2)
        .mediaSegmentCount(180)
        .outputFrameRate(Optional.of(new OutputFrameRate(24, 1)))
        .transcodeDecision(decision(false))
        .encoderPreset(EncoderPreset.FASTER)
        .width(64)
        .height(36)
        .bitrate(100_000L)
        .readFromLastKeyframePresentingAtOrBefore(Optional.of(searchTime))
        .advertisedEnd(Optional.of(Duration.ofSeconds(360)))
        .build();
  }

  // The request with the server's stored seek position.
  private static TranscodeRequest storing(Duration seekPosition, TranscodeRequest request) {
    return request.toBuilder().seekPosition(Optional.of(seekPosition)).build();
  }

  private static TranscodeRequest holding(TranscodeRequest request) {
    return request.toBuilder().transcodeDecision(decision(true)).build();
  }

  private static TranscodeDecision decision(boolean holdsLastFrame) {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily("h264")
        .videoDecision(
            VideoDecisionFixtures.encodeBuilder()
                .holdsLastFrameToAdvertisedEnd(holdsLastFrame)
                .build())
        .audioDecision(
            AudioDecision.builder()
                .mode(AudioMode.TRANSCODE)
                .codec("aac")
                .channels(2)
                .bitrate(128_000L)
                .padsWithSilenceToAdvertisedEnd(true)
                .build())
        .subtitleDecision(SubtitleDecisions.EXCLUDED)
        .build();
  }

  private static Duration decodeTimeOf(int presentationSeconds) {
    return Duration.ofSeconds(presentationSeconds).minus(ScriptedKeyframeSource.REORDER_DELAY);
  }

  private static Duration seconds(String seconds) {
    return Duration.ofNanos(new BigDecimal(seconds).movePointRight(9).longValueExact());
  }
}
