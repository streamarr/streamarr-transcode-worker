package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.awaitility.Awaitility.await;

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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
  // A stored seek position that no search check uses, and the seek argument of its read.
  private static final Duration STORED_POSITION = Duration.ofMillis(21_800);
  private static final String STORED_POSITION_ARGUMENT = "21.8";
  // A first picture check's copies, in the order FFmpeg reads them, of a read up to 24.5 s: a
  // second past the search time of 23.5 s.
  private static final String COPIES_BEFORE_A_SECOND_PAST_THE_SEARCH_TIME =
      """
      #tb 0: 1/1000
      #tb 1: 1/1000
      0, 24250, 24333, 250, 1000, 0x0a1b2c3d, F=0x0
      1, 24250, 24250, 250, 300, 0x0a1b2c3d
      0, 24499, 24583, 250, 1000, 0x0a1b2c3d, F=0x0
      1, 24750, 24750, 250, 300, 0x0a1b2c3d
      """;
  private static final String COPIES_UP_TO_A_SECOND_PAST_THE_SEARCH_TIME =
      """
      #tb 0: 1/1000
      #tb 1: 1/1000
      0, 24250, 24333, 250, 1000, 0x0a1b2c3d, F=0x0
      1, 24250, 24250, 250, 300, 0x0a1b2c3d
      0, 24500, 24583, 250, 1000, 0x0a1b2c3d, F=0x0
      1, 24500, 24500, 250, 300, 0x0a1b2c3d
      """;

  // A first picture check's copies of a read inside the GOP from 20 s of a video that ends at 22 s:
  // the last video packet presents from 21.958 s for 42 ms, and the audio runs on to 600 s.
  private static final String COPIES_UP_TO_A_VIDEO_END_AT_22_SECONDS =
      """
      #tb 0: 1/1000
      #tb 1: 1/1000
      0, 21792, 21875, 42, 1000, 0x0a1b2c3d, F=0x0
      1, 21800, 21800, 250, 300, 0x0a1b2c3d
      0, 21875, 21958, 42, 1000, 0x0a1b2c3d, F=0x0
      1, 22050, 22050, 250, 300, 0x0a1b2c3d
      1, 60000, 60000, 250, 300, 0x0a1b2c3d
      1, 600000, 600000, 250, 300, 0x0a1b2c3d
      """;

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

  // On the 2 s grid, segment 1 starts at 2 s, and the search's first interval, one period before
  // the search time of 1.5 s, would start before the source's start. The keyframe at 1 s presents
  // in time, but a seek to its time could land in a later copy of a time that the source repeats.
  @Test
  @DisplayName(
      "Should read from the source's start without a scan or a read check when the search's first"
          + " interval reaches the source's start")
  void shouldReadFromTheSourcesStartWithoutAScanWhenTheSearchsFirstIntervalReachesIt()
      throws Exception {
    var source = new ScriptedKeyframeSource(List.of(Duration.ZERO, Duration.ofSeconds(1)), 20);

    var readPosition =
        engineReading(source).readPosition(searching(seconds("1.5"), 2), bytesRead::addAndGet);

    assertThat(readPosition).contains(Duration.ZERO);
    assertThat(source.scannedIntervals()).isEmpty();
    assertThat(source.readChecks()).isEmpty();
    assertThat(source.firstPictureChecks()).isEmpty();
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

  // The server stores the keyframe at 22 s and sends its decode time. Each FFmpeg that runs before
  // the attempt's own delays its first segment, so the stored position's read is decoded once and
  // not first copied to see where it lands.
  @Test
  @DisplayName(
      "Should keep the stored seek position after one first picture check, without a landing check"
          + " or a scan, when its read shows the keyframe's picture at or before the search time")
  void shouldKeepTheStoredSeekPositionWhenItsReadShowsThePictureAtOrBeforeTheSearchTime()
      throws Exception {
    var source = everyTwoSecondsUpTo(30, 30);

    var readPosition =
        engineReading(source)
            .readPosition(
                storing(decodeTimeOf(22), searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(readPosition).contains(decodeTimeOf(22));
    assertThat(source.firstPictureChecks()).containsExactly(decodeTimeOf(22));
    assertThat(source.readChecks()).isEmpty();
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
        .containsExactly(Duration.ofSeconds(22), decodeTimeOf(22), seconds("21.786566"));
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
            Duration.ofSeconds(22), decodeTimeOf(22), Duration.ofSeconds(20), decodeTimeOf(20));
  }

  // From the recovery point at 22 s the decoder shows its first picture at 27 s, 5 s later, after
  // the second past the search time, 24.5 s, at which each check of it stops reading. The scripted
  // check shows that picture, which counts as none in time.
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
  }

  // The stored position's read lands inside a GOP whose decoder shows nothing before 24.5 s, a
  // second past the search time. FFmpeg writes the packets that the check copies as it reads them.
  @Test
  @Timeout(10)
  @DisplayName(
      "Should stop the first picture check with q, and search, when a copied video packet decodes a"
          + " second past the search time before the decoder shows a picture")
  void shouldStopTheFirstPictureCheckWhenACopiedVideoPacketDecodesASecondPastTheSearchTime()
      throws Exception {
    var check =
        ScriptedProcess.builder()
            .output(new byte[0])
            .stderr(COPIES_UP_TO_A_SECOND_PAST_THE_SEARCH_TIME)
            .exitTiming(ScriptedProcess.ExitTiming.AT_QUIT)
            .build();
    var source = everyTwoSecondsUpTo(30, 30);

    var readPosition =
        engine(checkingTheStoredPositionWith(check, source))
            .readPosition(
                storing(STORED_POSITION, searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(check.stdinText()).isEqualTo("q");
    assertThat(readPosition).contains(decodeTimeOf(22));
  }

  // The check's last copied video packet decodes at 24.499 s, a millisecond before the second past
  // the search time; the audio packet after it, at 24.75 s, does not end the read.
  @Test
  @Timeout(10)
  @DisplayName(
      "Should let the first picture check read on without a q while every copied video packet"
          + " decodes before a second past the search time")
  void shouldLetTheFirstPictureCheckReadOnWhileEveryCopiedVideoPacketDecodesBeforeTheReadEnd()
      throws Exception {
    var check =
        ScriptedProcess.builder()
            .output(new byte[0])
            .stderr(COPIES_BEFORE_A_SECOND_PAST_THE_SEARCH_TIME)
            .exitTiming(ScriptedProcess.ExitTiming.WHEN_TEST_EXITS)
            .build();
    var engine = engine(checkingTheStoredPositionWith(check, everyTwoSecondsUpTo(30, 30)));
    var request = storing(STORED_POSITION, searching(seconds("23.5"), 24));
    var reading = new FutureTask<>(() -> engine.readPosition(request, bytesRead::addAndGet));
    Thread.ofVirtual().start(reading);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(bytesRead).as("every copied packet").hasValue(2_600));
    assertThat(check.stdinText()).isEmpty();
    check.exit();

    assertThat(reading.get(5, TimeUnit.SECONDS)).contains(decodeTimeOf(22));
  }

  // The decoder shows the picture at 23.5 s only once FFmpeg, stopped by the q, decodes the
  // packets that it has read.
  @Test
  @Timeout(10)
  @DisplayName(
      "Should keep the stored seek position when the decoder shows its picture in time only after"
          + " the q stops the first picture check")
  void shouldKeepTheStoredSeekPositionWhenTheDecoderShowsItsPictureOnlyAfterTheQ()
      throws Exception {
    var check =
        ScriptedProcess.builder()
            .output(
                "#tb 0: 1/1000\n0, 23500, 23500, 1, 500, 0x0a1b2c3d\n"
                    .getBytes(StandardCharsets.US_ASCII))
            .pauseAfter(0)
            .resumesOnQuit(true)
            .stderr(COPIES_UP_TO_A_SECOND_PAST_THE_SEARCH_TIME)
            .build();
    var source = everyTwoSecondsUpTo(30, 30);

    var readPosition =
        engine(checkingTheStoredPositionWith(check, source))
            .readPosition(
                storing(STORED_POSITION, searching(seconds("23.5"), 24)), bytesRead::addAndGet);

    assertThat(check.stdinText()).isEqualTo("q");
    assertThat(readPosition).contains(STORED_POSITION);
    assertThat(source.scannedIntervals()).isEmpty();
  }

  // A held encode's video ends at 22 s, before 24.5 s, a second past the search time. The stored
  // position's read lands inside the GOP from 20 s, so its decoder shows no picture, and its copies
  // run on through the audio to 600 s. FFmpeg quits only on q, as a real one ends only at the
  // source's end.
  @Test
  @Timeout(10)
  @DisplayName(
      "Should stop the first picture check once a copied video packet ends at the video's end, and"
          + " search, when the video ends before a second past the search time")
  void shouldStopTheFirstPictureCheckAtTheVideosEndWhenTheVideoEndsBeforeTheReadEnd()
      throws Exception {
    var check =
        ScriptedProcess.builder()
            .output(new byte[0])
            .stderr(COPIES_UP_TO_A_VIDEO_END_AT_22_SECONDS)
            .exitTiming(ScriptedProcess.ExitTiming.AT_QUIT)
            .build();
    var source = everyTwoSecondsUpTo(20, 22);

    var readPosition =
        engine(checkingTheStoredPositionWith(check, source))
            .readPosition(
                endingVideoAt(
                    Duration.ofSeconds(22),
                    storing(STORED_POSITION, holding(searching(seconds("23.5"), 24)))),
                bytesRead::addAndGet);

    assertThat(check.stdinText()).as("the q that ends the check").isEqualTo("q");
    assertThat(readPosition).contains(decodeTimeOf(18));
  }

  // The last copied video packet ends at exactly 22 s on the check's timeline. The server's video
  // end subtracts the container's start time in whole microseconds, and FFmpeg's -start_at_zero
  // subtracts it rounded to the nearest tick of the video's time base, so the server's end lies up
  // to half a tick later.
  @ParameterizedTest(name = "{0}")
  @MethodSource("videoEndsUpToHalfATickPastTheLastCopiedPacket")
  @Timeout(10)
  @DisplayName(
      "Should stop the first picture check when the copied video packet that ends the video ends up"
          + " to half a tick before the video's end")
  void shouldStopTheCheckWhenThePacketThatEndsTheVideoEndsUpToHalfATickBeforeTheVideosEnd(
      String startTimeCase, String copies, Duration videoEnd) throws Exception {
    var check =
        ScriptedProcess.builder()
            .output(new byte[0])
            .stderr(copies)
            .exitTiming(ScriptedProcess.ExitTiming.AT_QUIT)
            .build();

    engine(checkingTheStoredPositionWith(check, everyTwoSecondsUpTo(20, 22)))
        .readPosition(
            endingVideoAt(
                videoEnd, storing(STORED_POSITION, holding(searching(seconds("23.5"), 24)))),
            bytesRead::addAndGet);

    assertThat(check.stdinText()).as("the q that ends the check").isEqualTo("q");
  }

  // The 1/12288 case is the pinned FFmpeg's, on an MP4 whose AAC starts 2 ms into the file.
  static Stream<Arguments> videoEndsUpToHalfATickPastTheLastCopiedPacket() {
    return Stream.of(
        Arguments.of(
            "ticks of 1/12288: a start at 2 ms is 24.576 ticks, which FFmpeg rounds to 25, so the"
                + " end lies 34.505 µs later",
            """
            #tb 0: 1/12288
            #tb 1: 1/48000
            0, 268288, 268800, 512, 1000, 0x0a1b2c3d, F=0x0
            1, 1046400, 1046400, 1024, 300, 0x0a1b2c3d
            0, 268800, 269824, 512, 1000, 0x0a1b2c3d, F=0x0
            1, 28800000, 28800000, 1024, 300, 0x0a1b2c3d
            """,
            Duration.ofSeconds(22).plusNanos(34_505)),
        Arguments.of(
            "ticks of 1/90000: a start at 50 µs is 4.5 ticks, which FFmpeg rounds to 5, so the end"
                + " lies half a tick, 5,555.6 ns, later, which the server rounds to 5,556 ns",
            """
            #tb 0: 1/90000
            #tb 1: 1/90000
            0, 1964250, 1972500, 3750, 1000, 0x0a1b2c3d, F=0x0
            1, 1962000, 1962000, 1920, 300, 0x0a1b2c3d
            0, 1968000, 1976250, 3750, 1000, 0x0a1b2c3d, F=0x0
            1, 54000000, 54000000, 1920, 300, 0x0a1b2c3d
            """,
            Duration.ofSeconds(22).plusNanos(5_556)));
  }

  // Neither copied video packet decodes at 24.5 s, a second past the search time, and the video's
  // end lies past both, so FFmpeg copies the audio on to the end of the file and exits.
  @ParameterizedTest(name = "{0}")
  @MethodSource("videoEndsThatNoCopiedPacketReaches")
  @Timeout(10)
  @DisplayName(
      "Should let the first picture check read to the end of the file without a q when no copied"
          + " video packet reaches the read end or the video's end")
  void shouldLetTheCheckReadToTheEndWhenNoCopiedVideoPacketReachesTheReadEndOrTheVideosEnd(
      String videoEndCase, Optional<Duration> videoEnd) throws Exception {
    var check =
        ScriptedProcess.builder()
            .output(new byte[0])
            .stderr(COPIES_UP_TO_A_VIDEO_END_AT_22_SECONDS)
            .outputAwaitsErrorOutput(true)
            .build();
    var request =
        storing(STORED_POSITION, holding(searching(seconds("23.5"), 24))).toBuilder()
            .videoEnd(videoEnd)
            .build();

    var readPosition =
        engine(checkingTheStoredPositionWith(check, everyTwoSecondsUpTo(20, 22)))
            .readPosition(request, bytesRead::addAndGet);

    assertThat(check.stdinText()).as("no q").isEmpty();
    assertThat(readPosition).contains(decodeTimeOf(18));
  }

  static Stream<Arguments> videoEndsThatNoCopiedPacketReaches() {
    return Stream.of(
        Arguments.of("the video's end is unknown", Optional.empty()),
        Arguments.of(
            "every copied video packet ends at least a whole tick, 1 ms, before the video's end",
            Optional.of(Duration.ofMillis(22_001))));
  }

  // The video ends at 24.55 s, just past 24.5 s, a second past the search time. The copies list
  // the read's last two video packets: the last decodes at 24.467 s, before 24.5 s, so only the
  // video's end stops the check.
  @Test
  @Timeout(10)
  @DisplayName(
      "Should stop the first picture check at the video's end when the video ends just past the"
          + " read end and no copied video packet decodes at the read end")
  void shouldStopTheCheckAtTheVideosEndWhenTheVideoEndsJustPastTheReadEnd() throws Exception {
    var check =
        ScriptedProcess.builder()
            .output(new byte[0])
            .stderr(
                """
                #tb 0: 1/1000
                #tb 1: 1/1000
                0, 24383, 24466, 42, 1000, 0x0a1b2c3d, F=0x0
                1, 24400, 24400, 250, 300, 0x0a1b2c3d
                0, 24467, 24508, 42, 1000, 0x0a1b2c3d, F=0x0
                1, 600000, 600000, 250, 300, 0x0a1b2c3d
                """)
            .exitTiming(ScriptedProcess.ExitTiming.AT_QUIT)
            .build();

    engine(checkingTheStoredPositionWith(check, everyTwoSecondsUpTo(24, 25)))
        .readPosition(
            endingVideoAt(
                Duration.ofMillis(24_550),
                storing(STORED_POSITION, holding(searching(seconds("23.5"), 24)))),
            bytesRead::addAndGet);

    assertThat(check.stdinText()).as("the q that ends the check").isEqualTo("q");
  }

  // The read lands on the video's last keyframe, which presents last, up to the video's end. Its
  // three leading pictures follow it in decode order, need no picture before it, as HEVC's RADL
  // pictures do, and present before the search time of 23.5 s. FFmpeg shows the decoder's first
  // picture only at the end of the file, once the worker has read every copy.
  @ParameterizedTest(name = "{0}")
  @MethodSource("readsThatLandOnAKeyframeThatEndsTheVideoAfterItsLeadingPictures")
  @Timeout(10)
  @DisplayName(
      "Should let the first picture check read on past the keyframe that ends the video when its"
          + " leading pictures can present by the search time")
  void shouldLetTheCheckReadOnPastTheKeyframeThatEndsTheVideoWhenItsLeadingPicturesCanBeInTime(
      String containerCase, String copies, Duration videoEnd, Duration firstPicture)
      throws Exception {
    var picture = firstPicture.toMillis();
    var check =
        ScriptedProcess.builder()
            .output(
                "#tb 0: 1/1000\n0, %d, %d, 1, 500, 0x0a1b2c3d\n"
                    .formatted(picture, picture)
                    .getBytes(StandardCharsets.US_ASCII))
            .pauseAfter(0)
            .stderr(copies)
            .build();
    var bytesOfEveryCopy = 4 * 1000 + 3 * 300;
    LongConsumer readingToTheEndOfTheFile =
        bytes -> {
          if (bytesRead.addAndGet(bytes) == bytesOfEveryCopy) {
            check.resume();
          }
        };

    var readPosition =
        engine(checkingTheStoredPositionWith(check, everyTwoSecondsUpTo(22, 24)))
            .readPosition(
                endingVideoAt(videoEnd, storing(STORED_POSITION, searching(seconds("23.5"), 24))),
                readingToTheEndOfTheFile);

    assertThat(check.stdinText()).as("no q before the leading pictures").isEmpty();
    assertThat(readPosition).contains(STORED_POSITION);
  }

  // The Matroska case is the pinned FFmpeg's copy of the smoke test's read from 4.2 s of an x265
  // encode at 4 fps, 20 s later: it decodes the keyframe two pictures before it presents, and the
  // leading pictures 1 ms after it.
  static Stream<Arguments> readsThatLandOnAKeyframeThatEndsTheVideoAfterItsLeadingPictures() {
    return Stream.of(
        Arguments.of(
            "MP4 stores each packet's decode time: the keyframe decodes at 22.5 s and presents at"
                + " 23.75 s, and its leading pictures present from 23 s",
            """
            #tb 0: 1/1000
            #tb 1: 1/1000
            0, 22500, 23750, 250, 1000, 0x0a1b2c3d
            1, 22500, 22500, 250, 300, 0x0a1b2c3d
            0, 22750, 23250, 250, 1000, 0x0a1b2c3d, F=0x0
            0, 23000, 23000, 250, 1000, 0x0a1b2c3d, F=0x0
            0, 23250, 23500, 250, 1000, 0x0a1b2c3d, F=0x0
            1, 60000, 60000, 250, 300, 0x0a1b2c3d
            1, 600000, 600000, 250, 300, 0x0a1b2c3d
            """,
            Duration.ofSeconds(24),
            Duration.ofSeconds(23)),
        Arguments.of(
            "Matroska stores no decode time: FFmpeg's copy decodes the keyframe at the search time,"
                + " 23.5 s, half a second before it presents, and its leading pictures present from"
                + " 23.25 s",
            """
            #tb 0: 1/1000
            #tb 1: 1/1000
            0, 23500, 24000, 250, 1000, 0x0a1b2c3d
            1, 23500, 23500, 250, 300, 0x0a1b2c3d
            0, 23501, 23501, 250, 1000, 0x0a1b2c3d, F=0x0
            0, 23501, 23501, 250, 1000, 0x0a1b2c3d, F=0x0
            0, 23501, 23750, 250, 1000, 0x0a1b2c3d, F=0x0
            1, 60000, 60000, 250, 300, 0x0a1b2c3d
            1, 600000, 600000, 250, 300, 0x0a1b2c3d
            """,
            Duration.ofMillis(24_250),
            Duration.ofMillis(23_250)));
  }

  // The packet that ends the video presents last, so no keyframe follows it in decode order. A
  // read that holds no keyframe shows no picture at all, and one with a keyframe that presents by
  // the search time shows a picture by then from the packets that it has read.
  @ParameterizedTest(name = "{0}")
  @MethodSource("readsThatShowNoPictureAfterThePacketThatEndsTheVideoByTheSearchTime")
  @Timeout(10)
  @DisplayName(
      "Should stop the first picture check at the packet that ends the video when the read holds no"
          + " keyframe or one that presents by the search time")
  void shouldStopTheCheckAtThePacketThatEndsTheVideoWhenTheReadHoldsNoKeyframeOrOneInTime(
      String readCase, String copies, Duration videoEnd) throws Exception {
    var check =
        ScriptedProcess.builder()
            .output(new byte[0])
            .stderr(copies)
            .exitTiming(ScriptedProcess.ExitTiming.AT_QUIT)
            .build();

    engine(checkingTheStoredPositionWith(check, everyTwoSecondsUpTo(20, 22)))
        .readPosition(
            endingVideoAt(
                videoEnd, storing(STORED_POSITION, holding(searching(seconds("23.5"), 24)))),
            bytesRead::addAndGet);

    assertThat(check.stdinText()).as("the q that ends the check").isEqualTo("q");
  }

  // In each case, the packet that ends the video decodes before the search time of 23.5 s.
  static Stream<Arguments> readsThatShowNoPictureAfterThePacketThatEndsTheVideoByTheSearchTime() {
    return Stream.of(
        Arguments.of(
            "the read holds no keyframe, and the packet presents from 23.75 s up to the video's"
                + " end at 24 s",
            """
            #tb 0: 1/1000
            #tb 1: 1/1000
            0, 23000, 23250, 250, 1000, 0x0a1b2c3d, F=0x0
            1, 23000, 23000, 250, 300, 0x0a1b2c3d
            0, 23250, 23750, 250, 1000, 0x0a1b2c3d, F=0x0
            1, 600000, 600000, 250, 300, 0x0a1b2c3d
            """,
            Duration.ofSeconds(24)),
        Arguments.of(
            "the read's keyframe presents at 23.5 s, and the packet presents from 23.75 s up to the"
                + " video's end at 24 s",
            """
            #tb 0: 1/1000
            #tb 1: 1/1000
            0, 23000, 23500, 250, 1000, 0x0a1b2c3d
            1, 23000, 23000, 250, 300, 0x0a1b2c3d
            0, 23250, 23750, 250, 1000, 0x0a1b2c3d, F=0x0
            1, 600000, 600000, 250, 300, 0x0a1b2c3d
            """,
            Duration.ofSeconds(24)),
        Arguments.of(
            "the read's first keyframe presents at 23 s, and the packet is a later keyframe that"
                + " presents from 23.75 s up to the video's end at 24 s",
            """
            #tb 0: 1/1000
            #tb 1: 1/1000
            0, 22750, 23000, 250, 1000, 0x0a1b2c3d
            1, 22750, 22750, 250, 300, 0x0a1b2c3d
            0, 23000, 23750, 250, 1000, 0x0a1b2c3d
            1, 600000, 600000, 250, 300, 0x0a1b2c3d
            """,
            Duration.ofSeconds(24)),
        Arguments.of(
            "the packet is the read's only keyframe and presents at 23.5 s, up to the video's end"
                + " at 23.75 s",
            """
            #tb 0: 1/1000
            #tb 1: 1/1000
            0, 23250, 23500, 250, 1000, 0x0a1b2c3d
            1, 23250, 23250, 250, 300, 0x0a1b2c3d
            1, 600000, 600000, 250, 300, 0x0a1b2c3d
            """,
            Duration.ofMillis(23_750)));
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

  // A time base of 1/0 gives the copied packet no time that the worker can read. Without its end,
  // the scripted FFmpeg would wait for the reader of its copies, as a real one would.
  @Test
  @Timeout(10)
  @DisplayName(
      "Should fail the search, and end the check's FFmpeg, when the time of a packet that the first"
          + " picture check copied cannot be read")
  void shouldFailTheSearchAndEndTheChecksFfmpegWhenACopiedPacketsTimeCannotBeRead() {
    var check =
        ScriptedProcess.builder()
            .output(new byte[0])
            .stderr("#tb 0: 1/0\n0, 24250, 24333, 250, 1000, 0x0a1b2c3d, F=0x0\n")
            .exitTiming(ScriptedProcess.ExitTiming.WHEN_TEST_EXITS)
            .build();
    var engine = engine(checkingTheStoredPositionWith(check, everyTwoSecondsUpTo(30, 30)));
    var request = storing(STORED_POSITION, searching(seconds("23.5"), 24));

    assertThatExceptionOfType(TranscodeException.class)
        .isThrownBy(() -> engine.readPosition(request, bytesRead::addAndGet));
    assertThat(check.wasDestroyedForcibly()).isTrue();
  }

  // The stored position's first picture check runs the given FFmpeg; every other check and scan
  // reads the source.
  private static ProcessLauncher checkingTheStoredPositionWith(
      ScriptedProcess check, ScriptedKeyframeSource source) {
    return ScriptedProcessLauncher.forCommands(
        (command, _) ->
            ScriptedKeyframeSource.isFirstPictureCheck(command)
                    && command.contains(STORED_POSITION_ARGUMENT)
                ? check
                : source.scan(command));
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

  // The request with the chosen video's end that the server read from a keyframe packet scan.
  private static TranscodeRequest endingVideoAt(Duration videoEnd, TranscodeRequest request) {
    return request.toBuilder().videoEnd(Optional.of(videoEnd)).build();
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
