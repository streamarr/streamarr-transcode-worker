package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.SourceEnd.EndedEarly;
import com.streamarr.transcode.engine.SourceEnd.StreamEnd;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Source End Rule Tests")
class SourceEndRuleTest {

  private static final OptionalInt NOTHING_DELIVERED = OptionalInt.empty();
  private static final int VIDEO_INDEX = 0;
  private static final int AUDIO_INDEX = 1;

  private static final ObservedSourceStream VIDEO_ENDING_AT_64_SECONDS =
      videoEndingAt64Seconds().build();
  private static final ObservedSourceStream AUDIO_ENDING_AT_84_SECONDS =
      audioEndingAt84Seconds().build();

  private static final TimeBase MPEG_TS_TIME_BASE = new TimeBase(1, 90_000);
  private static final TimeBase AUDIO_TIME_BASE = new TimeBase(1, 48_000);
  // Segments start at 0 s, at the MPEG-TS tick 614,400 (6.8266... s) and at 10 s.
  private static final SegmentIntervals EXACT_STARTS_ON_MPEG_TS_TICKS =
      SegmentIntervals.onExactStarts(
          3, new ExactSegmentStarts(List.of(0L, 614_400L, 900_000L), 1, 90_000));

  private static final List<StreamEnd> BOTH_ENDS =
      List.of(
          new StreamEnd(VIDEO_INDEX, Duration.ofSeconds(64)),
          new StreamEnd(AUDIO_INDEX, Duration.ofSeconds(84)));

  @Test
  @DisplayName(
      "Should report the segment of the video's last frame, and the audio after it, when an encode"
          + " that holds no frame and delivered no segment reads video that ends before the audio")
  void shouldReportTheSegmentOfTheVideosLastFrameWhenAnEncodeHoldsNoFrameAndDeliveredNone() {
    var report =
        ruleFor(VideoDecisionFixtures.encodeBuilder().streamIndex(VIDEO_INDEX).build())
            .reportOn(
                List.of(VIDEO_ENDING_AT_64_SECONDS, AUDIO_ENDING_AT_84_SECONDS), NOTHING_DELIVERED);

    assertThat(report)
        .isEqualTo(new SourceEnd(BOTH_ENDS, Optional.of(new EndedEarly(10, List.of(AUDIO_INDEX)))));
  }

  // The last frame starts at 0 s and lasts 20 s. The encode repeats it at the output rate up to its
  // end, so its encoded frames reach segment 3, from 18 s to 24 s.
  @Test
  @DisplayName(
      "Should report the segment where the video's last frame ends when an encode that holds no"
          + " frame and delivered no segment reads a last frame that spans several segments")
  void shouldReportTheSegmentWhereTheLastFrameEndsWhenAnUndeliveredEncodesLastFrameSpansSegments() {
    var video =
        videoEndingAt64Seconds()
            .lastStart(MediaTime.of(Duration.ZERO))
            .end(MediaTime.of(Duration.ofSeconds(20)))
            .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ZERO)))
            .build();

    var report =
        ruleFor(VideoDecisionFixtures.encodeBuilder().streamIndex(VIDEO_INDEX).build())
            .reportOn(List.of(video), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(3, List.of()));
  }

  @Test
  @DisplayName(
      "Should report the segment of the video's last keyframe, listing only the audio, when a stream"
          + " copy that delivered no segment reads frames after it in a later interval")
  void shouldReportTheSegmentOfTheVideosLastKeyframeWhenAStreamCopyReadsFramesAfterIt() {
    var video =
        videoEndingAt64Seconds()
            .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ofMillis(59_000))))
            .build();

    var report =
        ruleFor(copy()).reportOn(List.of(video, AUDIO_ENDING_AT_84_SECONDS), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(9, List.of(AUDIO_INDEX)));
  }

  @Test
  @DisplayName(
      "Should report the merged segment that holds the video's last keyframe, and the audio after"
          + " its listed end, when a stream copy's job carries a segment list")
  void shouldReportTheMergedSegmentThatHoldsTheVideosLastKeyframeWhenAStreamCopysJobCarriesAList() {
    var video =
        videoEndingAt64Seconds()
            .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ofMillis(59_000))))
            .build();
    // 6 s slots, with each start in whole seconds: segment 8 spans slots 8 and 9, from 48 s to
    // 60 s.
    var rule =
        SourceEndRule.builder()
            .intervals(
                SegmentIntervals.onExactStarts(
                    6,
                    new ExactSegmentStarts(
                        List.of(0L, 6L, 12L, 18L, 24L, 30L, 36L, 42L, 48L, 60L, 78L), 1, 1)))
            .mediaSegmentCount(11)
            .video(copy())
            .build();

    var report = rule.reportOn(List.of(video, AUDIO_ENDING_AT_84_SECONDS), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(8, List.of(AUDIO_INDEX)));
  }

  // Frame-rate conversion can move the last source frame, at 63.96 s, into the next segment.
  @Test
  @DisplayName(
      "Should report the last delivered segment when an attempt that holds no frame delivered one"
          + " after the segment of the source's last frame")
  void shouldReportTheLastDeliveredSegmentWhenAnAttemptThatHoldsNoFrameDeliveredOneAfterIt() {
    var report =
        ruleFor(VideoDecisionFixtures.encodeBuilder().streamIndex(VIDEO_INDEX).build())
            .reportOn(
                List.of(VIDEO_ENDING_AT_64_SECONDS, AUDIO_ENDING_AT_84_SECONDS),
                OptionalInt.of(11));

    assertThat(report.endedEarly()).contains(new EndedEarly(11, List.of(AUDIO_INDEX)));
  }

  @Test
  @DisplayName(
      "Should decide from the source's streams, not the delivered segments, when an encode holds"
          + " the last frame")
  void shouldDecideFromTheSourcesStreamsWhenAnEncodeHoldsTheLastFrame() {
    var audio =
        audioEndingAt84Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(71_989)))
            .end(MediaTime.of(Duration.ofSeconds(72)))
            .build();

    var report =
        ruleFor(held()).reportOn(List.of(VIDEO_ENDING_AT_64_SECONDS, audio), OptionalInt.of(13));

    assertThat(report.endedEarly()).contains(new EndedEarly(11, List.of()));
  }

  @Test
  @DisplayName(
      "Should report no early end when an encode that holds the last frame reads audio that"
          + " reaches the last advertised segment")
  void shouldReportNoEarlyEndWhenAHeldEncodeReadsAudioThatReachesTheLastSegment() {
    var report =
        ruleFor(held())
            .reportOn(
                List.of(VIDEO_ENDING_AT_64_SECONDS, AUDIO_ENDING_AT_84_SECONDS), NOTHING_DELIVERED);

    assertThat(report).isEqualTo(new SourceEnd(BOTH_ENDS, Optional.empty()));
  }

  @Test
  @DisplayName(
      "Should report the segment of the latest stream and list none when an encode that holds the"
          + " last frame reads a source whose every stream ends early")
  void shouldReportTheLatestStreamsSegmentAndListNoneWhenAHeldEncodesSourceEndsEarly() {
    var audio =
        audioEndingAt84Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(71_989)))
            .end(MediaTime.of(Duration.ofSeconds(72)))
            .build();

    var report =
        ruleFor(held()).reportOn(List.of(VIDEO_ENDING_AT_64_SECONDS, audio), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(11, List.of()));
  }

  // fps repeats the source's last frame, from 5.5 s to 6.5 s, on the output grid into segment 1.
  @Test
  @DisplayName(
      "Should report the segment where the last source frame ends when an encode that holds the"
          + " last frame reads a final frame that spans a segment boundary")
  void shouldReportTheSegmentWhereTheLastFrameEndsWhenAHeldEncodesFinalFrameSpansABoundary() {
    var video =
        videoEndingAt64Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(5_500)))
            .end(MediaTime.of(Duration.ofMillis(6_500)))
            .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ofMillis(5_500))))
            .build();

    var report = ruleFor(held()).reportOn(List.of(video), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(1, List.of()));
  }

  @Test
  @DisplayName(
      "Should report the segment where the last source frame starts when an encode that holds the"
          + " last frame reads a final frame that ends on a segment boundary or lasts no time")
  void shouldReportTheSegmentWhereTheLastFrameStartsWhenItEndsOnABoundaryOrLastsNoTime() {
    var endingOnTheBoundary =
        videoEndingAt64Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(5_958)))
            .end(MediaTime.of(Duration.ofSeconds(6)))
            .build();
    var lastingNoTime =
        audioEndingAt84Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(5_000)))
            .end(MediaTime.of(Duration.ofMillis(5_000)))
            .build();

    var report =
        ruleFor(held()).reportOn(List.of(endingOnTheBoundary, lastingNoTime), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(0, List.of()));
  }

  // The last copied AAC packet runs from 5.994 s to 6.015 s; the producer places it in segment 0.
  @Test
  @DisplayName(
      "Should report the segment where the last copied audio packet starts when an encode that"
          + " holds the last frame copies audio whose last packet crosses a segment boundary")
  void shouldReportTheSegmentWhereTheLastCopiedAudioPacketStartsWhenItCrossesABoundary() {
    var video =
        videoEndingAt64Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(958)))
            .end(MediaTime.of(Duration.ofSeconds(1)))
            .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ZERO)))
            .build();
    var audio =
        audioEndingAt84Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(5_994)))
            .end(MediaTime.of(Duration.ofMillis(6_015)))
            .build();
    var rule =
        SourceEndRule.builder()
            .intervals(SegmentIntervals.onPlainGrid(6))
            .mediaSegmentCount(14)
            .video(held())
            .copiesAudio(true)
            .build();

    var report = rule.reportOn(List.of(video, audio), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(0, List.of()));
  }

  // Segment 1 starts at the MPEG-TS tick 614,400, 6.8266... s, which the copied audio packet at
  // 327,680 / 48,000 s starts exactly on; truncated to nanoseconds, it would fall in segment 0.
  @Test
  @DisplayName(
      "Should report the segment whose exact start a copied audio packet starts on when an encode"
          + " that holds the last frame reads its last audio there")
  void
      shouldReportTheSegmentWhoseExactStartTheLastCopiedAudioPacketStartsOnWhenAHeldEncodeReadsIt() {
    var video =
        videoEndingAt64Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(958)))
            .end(MediaTime.of(Duration.ofSeconds(1)))
            .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ZERO)))
            .build();
    var audio =
        audioEndingAt84Seconds()
            .lastStart(new MediaTime(327_680, AUDIO_TIME_BASE))
            .end(new MediaTime(328_704, AUDIO_TIME_BASE))
            .build();
    var rule =
        SourceEndRule.builder()
            .intervals(EXACT_STARTS_ON_MPEG_TS_TICKS)
            .mediaSegmentCount(3)
            .video(held())
            .copiesAudio(true)
            .build();

    var report = rule.reportOn(List.of(video, audio), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(1, List.of()));
  }

  @Test
  @DisplayName(
      "Should report the segment whose exact start a stream copy's last keyframe presents on when"
          + " the copy delivered no segment")
  void
      shouldReportTheSegmentWhoseExactStartAStreamCopysLastKeyframePresentsOnWhenItDeliveredNone() {
    var video =
        videoEndingAt64Seconds()
            .lastStart(new MediaTime(618_000, MPEG_TS_TIME_BASE))
            .end(new MediaTime(621_600, MPEG_TS_TIME_BASE))
            .lastKeyframeStart(Optional.of(new MediaTime(614_400, MPEG_TS_TIME_BASE)))
            .build();
    var rule =
        SourceEndRule.builder()
            .intervals(EXACT_STARTS_ON_MPEG_TS_TICKS)
            .mediaSegmentCount(3)
            .video(copy())
            .build();

    var report = rule.reportOn(List.of(video), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(1, List.of()));
  }

  @Test
  @DisplayName(
      "Should list the encoded audio after a segment only when its last sample ends after that"
          + " segment's exact end")
  void shouldListTheEncodedAudioAfterASegmentOnlyWhenItsLastSampleEndsAfterThatSegmentsExactEnd() {
    var video =
        videoEndingAt64Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(958)))
            .end(MediaTime.of(Duration.ofSeconds(1)))
            .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ZERO)))
            .build();
    var endingOnTheStart =
        audioEndingAt84Seconds()
            .lastStart(new MediaTime(326_656, AUDIO_TIME_BASE))
            .end(new MediaTime(327_680, AUDIO_TIME_BASE))
            .build();
    var rule =
        SourceEndRule.builder()
            .intervals(EXACT_STARTS_ON_MPEG_TS_TICKS)
            .mediaSegmentCount(3)
            .video(VideoDecisionFixtures.encodeBuilder().streamIndex(VIDEO_INDEX).build())
            .build();

    var report = rule.reportOn(List.of(video, endingOnTheStart), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(0, List.of()));
  }

  // The last audio packet runs from 65.9 s to 66.02 s, across the end of segment 10 at 66 s. An
  // encode converts it over its whole duration, so its encoded audio after 66 s has no delivered
  // segment; a copy places the packet whole in segment 10 by its start.
  @ParameterizedTest(name = "copies audio: {0}")
  @MethodSource("audioAfterTheLastSegmentByAudioMode")
  @DisplayName(
      "Should list the audio after the last segment only when it encodes the audio when the audio's"
          + " last packet crosses the end of the segment that holds the video's end")
  void shouldListTheAudioAfterTheLastSegmentOnlyWhenEncodedWhenItsLastPacketCrossesThatSegmentsEnd(
      boolean copiesAudio, List<Integer> streamsWithMediaAfter) {
    var audio =
        audioEndingAt84Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(65_900)))
            .end(MediaTime.of(Duration.ofMillis(66_020)))
            .build();
    var rule =
        SourceEndRule.builder()
            .intervals(SegmentIntervals.onPlainGrid(6))
            .mediaSegmentCount(14)
            .video(VideoDecisionFixtures.encodeBuilder().streamIndex(VIDEO_INDEX).build())
            .copiesAudio(copiesAudio)
            .build();

    var report = rule.reportOn(List.of(VIDEO_ENDING_AT_64_SECONDS, audio), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(10, streamsWithMediaAfter));
  }

  static Stream<Arguments> audioAfterTheLastSegmentByAudioMode() {
    return Stream.of(Arguments.of(false, List.of(AUDIO_INDEX)), Arguments.of(true, List.of()));
  }

  @Test
  @DisplayName(
      "Should report no early end when a stream copy's last keyframe lies in the last advertised"
          + " segment")
  void shouldReportNoEarlyEndWhenAStreamCopysLastKeyframeLiesInTheLastSegment() {
    var video =
        videoEndingAt64Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(83_958)))
            .end(MediaTime.of(Duration.ofSeconds(84)))
            .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ofSeconds(78))))
            .build();

    var report =
        ruleFor(copy()).reportOn(List.of(video, AUDIO_ENDING_AT_84_SECONDS), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report only the stream ends when the attempt read no keyframe of a stream copy's"
          + " video")
  void shouldReportOnlyTheStreamEndsWhenTheAttemptReadNoKeyframeOfACopysVideo() {
    var video = videoEndingAt64Seconds().lastKeyframeStart(Optional.empty()).build();

    var report =
        ruleFor(copy()).reportOn(List.of(video, AUDIO_ENDING_AT_84_SECONDS), NOTHING_DELIVERED);

    assertThat(report).isEqualTo(new SourceEnd(BOTH_ENDS, Optional.empty()));
  }

  @Test
  @DisplayName(
      "Should report only the audio's end when an encode that holds no frame read none of the"
          + " video")
  void shouldReportOnlyTheAudiosEndWhenAnEncodeReadNoneOfTheVideo() {
    var report =
        ruleFor(VideoDecisionFixtures.encodeBuilder().streamIndex(VIDEO_INDEX).build())
            .reportOn(List.of(AUDIO_ENDING_AT_84_SECONDS), NOTHING_DELIVERED);

    assertThat(report)
        .isEqualTo(
            new SourceEnd(
                List.of(new StreamEnd(AUDIO_INDEX, Duration.ofSeconds(84))), Optional.empty()));
  }

  @Test
  @DisplayName(
      "Should report no early end when the source's last sample starts before the timeline's zero")
  void shouldReportNoEarlyEndWhenTheSourcesLastSampleStartsBeforeZero() {
    var audio =
        audioEndingAt84Seconds()
            .lastStart(MediaTime.of(Duration.ofMillis(-21)))
            .end(MediaTime.of(Duration.ZERO))
            .build();

    var report = ruleFor(held()).reportOn(List.of(audio), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should say the source ends before the attempt's first segment only when its last segment"
          + " with source media comes before it")
  void shouldSayTheSourceEndsBeforeTheAttemptsFirstSegmentOnlyWhenItsLastSegmentComesBefore() {
    var report = new SourceEnd(BOTH_ENDS, Optional.of(new EndedEarly(10, List.of(AUDIO_INDEX))));

    assertThat(report.endsBefore(11)).isTrue();
    assertThat(report.endsBefore(10)).isFalse();
    assertThat(new SourceEnd(BOTH_ENDS, Optional.empty()).endsBefore(11)).isFalse();
  }

  // Video whose last frame starts at 63.96 s, inside segment 10, after its last keyframe at 60 s.
  private static ObservedSourceStream.ObservedSourceStreamBuilder videoEndingAt64Seconds() {
    return ObservedSourceStream.builder()
        .sourceIndex(VIDEO_INDEX)
        .lastStart(MediaTime.of(Duration.ofMillis(63_958)))
        .end(MediaTime.of(Duration.ofSeconds(64)))
        .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ofSeconds(60))));
  }

  // Audio whose last frame starts inside segment 13, the last of 14.
  private static ObservedSourceStream.ObservedSourceStreamBuilder audioEndingAt84Seconds() {
    return ObservedSourceStream.builder()
        .sourceIndex(AUDIO_INDEX)
        .lastStart(MediaTime.of(Duration.ofMillis(83_989)))
        .end(MediaTime.of(Duration.ofSeconds(84)))
        .lastKeyframeStart(Optional.of(MediaTime.of(Duration.ofMillis(83_989))));
  }

  // 14 segments of 6 s: the last advertised segment, 13, runs from 78 s to 84 s.
  private static SourceEndRule ruleFor(VideoDecision video) {
    return SourceEndRule.builder()
        .intervals(SegmentIntervals.onPlainGrid(6))
        .mediaSegmentCount(14)
        .video(video)
        .build();
  }

  private static VideoDecision copy() {
    return VideoDecisionFixtures.copyOf("avc1.640028");
  }

  private static VideoDecision held() {
    return VideoDecisionFixtures.encodeBuilder()
        .streamIndex(VIDEO_INDEX)
        .holdsLastFrameToAdvertisedEnd(true)
        .build();
  }
}
