package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Held Picture Read Position Tests")
class HeldPictureReadPositionTest {

  private final UUID attemptId = UUID.randomUUID();
  private final AtomicLong bytesRead = new AtomicLong();

  @Test
  @DisplayName(
      "Should keep the attempt's own seek position, after one scan, when a keyframe lies between it"
          + " and the first segment's start")
  void shouldKeepTheAttemptsOwnSeekPositionWhenAKeyframeLiesBeforeTheFirstSegmentsStart()
      throws Exception {
    var source =
        new ScriptedKeyframeSource(ScriptedKeyframeSource.keyframesEveryTwoSecondsUpTo(20), 20);
    var engine = engineReading(source);

    var readPosition = engine.heldPictureReadPosition(heldRequest(14, 18), bytesRead::addAndGet);

    assertThat(readPosition).isEmpty();
    assertThat(source.scannedIntervals()).containsExactly(interval(14, 18));
  }

  // The video's keyframes stop at 10 s and it ends at 20 s. The attempt for segment 4 reads back
  // from 14 s, so the search reads 4 s to 14 s, the read-back distance back from the read-back.
  @Test
  @DisplayName(
      "Should read from the last keyframe at least 0.5 s before the video's last keyframe when the"
          + " read-back lies after that keyframe")
  void shouldReadFromTheKeyframeHalfASecondBeforeTheLastKeyframeWhenTheReadBackLiesAfterIt()
      throws Exception {
    var source =
        new ScriptedKeyframeSource(ScriptedKeyframeSource.keyframesEveryTwoSecondsUpTo(10), 20);
    var engine = engineReading(source);

    var readPosition = engine.heldPictureReadPosition(heldRequest(14, 24), bytesRead::addAndGet);

    assertThat(readPosition)
        .as("an MPEG-TS seek to the keyframe's presentation time lands after its decode time")
        .contains(Duration.ofSeconds(8).minus(ScriptedKeyframeSource.REORDER_DELAY));
    assertThat(source.scannedIntervals()).containsExactly(interval(14, 24), interval(4, 14));
    assertThat(source.landingChecks())
        .containsExactly(
            Duration.ofSeconds(8),
            Duration.ofSeconds(8).minus(ScriptedKeyframeSource.REORDER_DELAY));
  }

  @Test
  @DisplayName(
      "Should read from the found keyframe's presentation time when a seek there lands on the"
          + " keyframe, as an MP4 source's seek does")
  void shouldReadFromTheFoundKeyframesPresentationTimeWhenASeekThereLandsOnTheKeyframe()
      throws Exception {
    var source =
        ScriptedKeyframeSource.seekingByPresentationTime(
            ScriptedKeyframeSource.keyframesEveryTwoSecondsUpTo(10), 20);
    var engine = engineReading(source);

    var readPosition = engine.heldPictureReadPosition(heldRequest(14, 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ofSeconds(8));
    assertThat(source.landingChecks()).containsExactly(Duration.ofSeconds(8));
  }

  // A raw H.264 stream's demuxer cannot seek, so a seek reads no packet at all.
  @Test
  @DisplayName(
      "Should read from the start of the timeline when no seek to the found keyframe's times reads a"
          + " video packet")
  void shouldReadFromTheStartOfTheTimelineWhenNoSeekToTheFoundKeyframesTimesReadsAPacket()
      throws Exception {
    var source =
        ScriptedKeyframeSource.seekingByPresentationTime(
            ScriptedKeyframeSource.keyframesEveryTwoSecondsUpTo(10), 20);
    var engine =
        engine(
            ScriptedProcessLauncher.forCommands(
                (command, _) ->
                    command.contains("-copypriorss")
                        ? ScriptedProcess.builder().output(new byte[0]).build()
                        : source.scan(command)));

    var readPosition = engine.heldPictureReadPosition(heldRequest(14, 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
  }

  @Test
  @DisplayName(
      "Should read intervals that run back from the read-back, each twice as long as the one after"
          + " it, until one holds a keyframe")
  void shouldReadIntervalsThatRunBackEachTwiceAsLongAsTheOneAfterItUntilOneHoldsAKeyframe()
      throws Exception {
    var source = new ScriptedKeyframeSource(List.of(seconds("0.5"), seconds("1")), 50);
    var engine = engineReading(source);

    var readPosition = engine.heldPictureReadPosition(heldRequest(40, 50), bytesRead::addAndGet);

    assertThat(readPosition)
        .contains(Duration.ofMillis(500).minus(ScriptedKeyframeSource.REORDER_DELAY));
    assertThat(source.scannedIntervals())
        .containsExactly(interval(40, 50), interval(30, 40), interval(10, 30), interval(0, 10));
  }

  // The last keyframe, at 13.8 s, opens the first interval; the keyframe 0.5 s before it lies two
  // intervals back.
  @Test
  @DisplayName(
      "Should go on reading earlier intervals for the keyframe 0.5 s before the last keyframe when"
          + " the interval that holds the last keyframe holds no earlier one")
  void shouldGoOnReadingEarlierIntervalsWhenTheLastKeyframesIntervalHoldsNoEarlierOne()
      throws Exception {
    var source =
        new ScriptedKeyframeSource(List.of(Duration.ZERO, seconds("3"), seconds("13.8")), 20);
    var engine = engineReading(source);

    var readPosition = engine.heldPictureReadPosition(heldRequest(14, 24), bytesRead::addAndGet);

    assertThat(readPosition)
        .contains(Duration.ofSeconds(3).minus(ScriptedKeyframeSource.REORDER_DELAY));
    assertThat(source.scannedIntervals())
        .containsExactly(interval(14, 24), interval(4, 14), interval(0, 4));
  }

  // The last keyframe presents at 10 s, where the second interval starts, and decodes 83 ms before,
  // inside the third interval: the scans cut where packets decode.
  @Test
  @DisplayName(
      "Should find the video's last keyframe in the interval where it decodes when it presents in"
          + " the interval after it")
  void shouldFindTheLastKeyframeInTheIntervalWhereItDecodesWhenItPresentsInTheIntervalAfterIt()
      throws Exception {
    var source =
        new ScriptedKeyframeSource(ScriptedKeyframeSource.keyframesEveryTwoSecondsUpTo(10), 20);
    var engine = engineReading(source);

    var readPosition = engine.heldPictureReadPosition(heldRequest(14, 18), bytesRead::addAndGet);

    assertThat(readPosition)
        .contains(Duration.ofSeconds(8).minus(ScriptedKeyframeSource.REORDER_DELAY));
    assertThat(source.scannedIntervals())
        .containsExactly(interval(14, 18), interval(10, 14), interval(2, 10));
  }

  @Test
  @DisplayName(
      "Should read from the video's first keyframe when no keyframe lies 0.5 s before its last")
  void shouldReadFromTheVideosFirstKeyframeWhenNoKeyframeLiesHalfASecondBeforeItsLast()
      throws Exception {
    var source = new ScriptedKeyframeSource(List.of(seconds("0.3"), seconds("0.6")), 20);
    var engine = engineReading(source);

    var readPosition = engine.heldPictureReadPosition(heldRequest(14, 24), bytesRead::addAndGet);

    assertThat(readPosition)
        .contains(Duration.ofMillis(300).minus(ScriptedKeyframeSource.REORDER_DELAY));
  }

  // The video's first keyframe decodes 83 ms before it presents at 0 s, before the timeline starts.
  @Test
  @DisplayName(
      "Should read from the start of the timeline when the keyframe that the search finds decodes"
          + " before it")
  void shouldReadFromTheStartOfTheTimelineWhenTheFoundKeyframeDecodesBeforeIt() throws Exception {
    var source = new ScriptedKeyframeSource(List.of(Duration.ZERO, Duration.ofSeconds(10)), 20);
    var engine = engineReading(source);

    var readPosition = engine.heldPictureReadPosition(heldRequest(14, 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
  }

  // A raw H.264 stream's packets carry decode times alone.
  @Test
  @DisplayName(
      "Should place a keyframe at its decode time when its packet carries no presentation time")
  void shouldPlaceAKeyframeAtItsDecodeTimeWhenItsPacketCarriesNoPresentationTime()
      throws Exception {
    var framecrc =
        """
        0, 9000, 9000, 250, 1000, 0x0a1b2c3d
        #tb 0: 1/1000
        0, 8000, -9223372036854775808, 250, 1000, 0x0a1b2c3d
        0, 10000, -9223372036854775808, 250, 1000, 0x0a1b2c3d
        0, -9223372036854775808, -9223372036854775808, 250, 1000, 0x0a1b2c3d
        """;
    var engine =
        engine(
            ScriptedProcessLauncher.forCommands(
                (command, _) ->
                    ScriptedProcess.builder()
                        .output(
                            command.contains("14")
                                ? new byte[0]
                                : framecrc.getBytes(StandardCharsets.US_ASCII))
                        .build()));

    var readPosition = engine.heldPictureReadPosition(heldRequest(14, 24), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ofSeconds(8));
  }

  // An MP4 clip that its edit list trims inside a GOP marks its keyframe, and every picture before
  // the clip's start, to decode but not to present.
  @Test
  @DisplayName(
      "Should read from the start of the timeline when the video's only keyframe decodes but is not"
          + " presented")
  void shouldReadFromTheStartOfTheTimelineWhenTheOnlyKeyframeDecodesButIsNotPresented()
      throws Exception {
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

    var readPosition = engine.heldPictureReadPosition(heldRequest(4, 8), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
  }

  @Test
  @DisplayName(
      "Should keep the attempt's own seek position and launch no scan when the read-back is the"
          + " start of the timeline")
  void shouldKeepTheSeekPositionAndLaunchNoScanWhenTheReadBackIsTheStartOfTheTimeline()
      throws Exception {
    var source = new ScriptedKeyframeSource(List.of(), 20);
    var engine = engineReading(source);

    var readPosition = engine.heldPictureReadPosition(heldRequest(0, 8), bytesRead::addAndGet);

    assertThat(readPosition).isEmpty();
    assertThat(source.scannedIntervals()).isEmpty();
  }

  @Test
  @DisplayName("Should fail the search when a scan's FFmpeg cannot start")
  void shouldFailTheSearchWhenAScansFfmpegCannotStart() {
    var engine =
        engine(
            (_, _) -> {
              throw new IOException("ffmpeg: not found");
            });
    var request = heldRequest(14, 24);

    assertThatExceptionOfType(TranscodeException.class)
        .isThrownBy(() -> engine.heldPictureReadPosition(request, bytesRead::addAndGet))
        .withCauseInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("Should fail the search when no keyframe lies before the read-back")
  void shouldFailTheSearchWhenNoKeyframeLiesBeforeTheReadBack() throws Exception {
    var source = new ScriptedKeyframeSource(List.of(), 20);
    var engine = engineReading(source);
    var request = heldRequest(14, 24);

    assertThatExceptionOfType(NoKeyframeBeforeReadBackException.class)
        .isThrownBy(() -> engine.heldPictureReadPosition(request, bytesRead::addAndGet));
  }

  @Test
  @DisplayName(
      "Should count the bytes of every packet that each scan read, video and audio, as the search's"
          + " bytes read")
  void shouldCountTheBytesOfEveryPacketThatEachScanReadAsTheSearchsBytesRead() throws Exception {
    var source =
        new ScriptedKeyframeSource(ScriptedKeyframeSource.keyframesEveryTwoSecondsUpTo(10), 20);
    var engine = engineReading(source);

    engine.heldPictureReadPosition(heldRequest(14, 24), bytesRead::addAndGet);

    assertThat(bytesRead.get()).isEqualTo(source.bytesWritten()).isPositive();
  }

  @Test
  @DisplayName("Should fail the search when a scan's FFmpeg exits with an error")
  void shouldFailTheSearchWhenAScansFfmpegExitsWithAnError() throws Exception {
    var engine =
        engine(
            ScriptedProcessLauncher.forCommands(
                (_, _) -> ScriptedProcess.builder().output(new byte[0]).exitCode(1).build()));
    var request = heldRequest(14, 24);

    assertThatExceptionOfType(TranscodeException.class)
        .isThrownBy(() -> engine.heldPictureReadPosition(request, bytesRead::addAndGet))
        .isNotInstanceOf(NoKeyframeBeforeReadBackException.class);
  }

  static Stream<Arguments> attemptsThatRunNoSearch() {
    var held = heldRequest(14, 24);
    return Stream.of(
        Arguments.of("an attempt from segment 0", requestFrom(held).startSequenceNumber(0).build()),
        Arguments.of(
            "an attempt without a seek position",
            requestFrom(held).seekPosition(Optional.empty()).build()),
        Arguments.of(
            "an encode that does not hold its last frame",
            requestFrom(held).transcodeDecision(decision(false)).build()),
        Arguments.of("an attempt that reads from its first segment's start", heldRequest(24, 24)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("attemptsThatRunNoSearch")
  @DisplayName(
      "Should keep the seek position and launch no scan when the attempt is not a held seek")
  void shouldKeepTheSeekPositionAndLaunchNoScanWhenTheAttemptIsNotAHeldSeek(
      String attempt, TranscodeRequest request) throws Exception {
    var source = new ScriptedKeyframeSource(List.of(), 20);
    var engine = engineReading(source);

    assertThat(engine.heldPictureReadPosition(request, bytesRead::addAndGet)).isEmpty();
    assertThat(source.scannedIntervals()).isEmpty();
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

  // A held encode on a 2 s grid whose first segment starts at that many seconds, read back from the
  // seek position.
  private static TranscodeRequest heldRequest(int seekSeconds, int firstSegmentStartSeconds) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .attemptId(UUID.randomUUID())
        .sourcePath(Path.of("/media/source.ts"))
        .targetSegmentDuration(2)
        .startSequenceNumber(firstSegmentStartSeconds / 2)
        .mediaSegmentCount(180)
        .outputFrameRate(Optional.of(new OutputFrameRate(24, 1)))
        .transcodeDecision(decision(true))
        .encoderPreset(EncoderPreset.FASTER)
        .width(64)
        .height(36)
        .bitrate(100_000L)
        .seekPosition(Optional.of(Duration.ofSeconds(seekSeconds)))
        .advertisedEnd(Optional.of(Duration.ofSeconds(360)))
        .build();
  }

  private static TranscodeRequest.TranscodeRequestBuilder requestFrom(TranscodeRequest request) {
    return TranscodeRequest.builder()
        .sessionId(request.sessionId())
        .attemptId(request.attemptId())
        .sourcePath(request.sourcePath())
        .targetSegmentDuration(request.targetSegmentDuration())
        .startSequenceNumber(request.startSequenceNumber())
        .mediaSegmentCount(request.mediaSegmentCount())
        .outputFrameRate(request.outputFrameRate())
        .transcodeDecision(request.transcodeDecision())
        .encoderPreset(request.encoderPreset())
        .width(request.width())
        .height(request.height())
        .bitrate(request.bitrate())
        .seekPosition(request.seekPosition())
        .advertisedEnd(request.advertisedEnd());
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

  private static Duration seconds(String seconds) {
    return Duration.ofNanos(new BigDecimal(seconds).movePointRight(9).longValueExact());
  }

  private static List<Duration> interval(int fromSeconds, int untilSeconds) {
    return List.of(Duration.ofSeconds(fromSeconds), Duration.ofSeconds(untilSeconds));
  }
}
