package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.SourceEnd.EndedEarly;
import com.streamarr.transcode.engine.SourceEnd.StreamEnd;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

  @Test
  @DisplayName(
      "Should report the segment of the video's last keyframe, listing only the audio, when a stream"
          + " copy that delivered no segment reads frames after it in a later interval")
  void shouldReportTheSegmentOfTheVideosLastKeyframeWhenAStreamCopyReadsFramesAfterIt() {
    var video =
        videoEndingAt64Seconds().lastKeyframeStart(Optional.of(Duration.ofMillis(59_000))).build();

    var report =
        ruleFor(copy()).reportOn(List.of(video, AUDIO_ENDING_AT_84_SECONDS), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(9, List.of(AUDIO_INDEX)));
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
            .lastStart(Duration.ofMillis(71_989))
            .end(Duration.ofSeconds(72))
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
            .lastStart(Duration.ofMillis(71_989))
            .end(Duration.ofSeconds(72))
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
            .lastStart(Duration.ofMillis(5_500))
            .end(Duration.ofMillis(6_500))
            .lastKeyframeStart(Optional.of(Duration.ofMillis(5_500)))
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
            .lastStart(Duration.ofMillis(5_958))
            .end(Duration.ofSeconds(6))
            .build();
    var lastingNoTime =
        audioEndingAt84Seconds()
            .lastStart(Duration.ofMillis(5_000))
            .end(Duration.ofMillis(5_000))
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
            .lastStart(Duration.ofMillis(958))
            .end(Duration.ofSeconds(1))
            .lastKeyframeStart(Optional.of(Duration.ZERO))
            .build();
    var audio =
        audioEndingAt84Seconds()
            .lastStart(Duration.ofMillis(5_994))
            .end(Duration.ofMillis(6_015))
            .build();
    var rule =
        SourceEndRule.builder()
            .periodSeconds(6)
            .mediaSegmentCount(14)
            .video(held())
            .copiesAudio(true)
            .build();

    var report = rule.reportOn(List.of(video, audio), NOTHING_DELIVERED);

    assertThat(report.endedEarly()).contains(new EndedEarly(0, List.of()));
  }

  @Test
  @DisplayName(
      "Should report no early end when a stream copy's last keyframe lies in the last advertised"
          + " segment")
  void shouldReportNoEarlyEndWhenAStreamCopysLastKeyframeLiesInTheLastSegment() {
    var video =
        videoEndingAt64Seconds()
            .lastStart(Duration.ofMillis(83_958))
            .end(Duration.ofSeconds(84))
            .lastKeyframeStart(Optional.of(Duration.ofSeconds(78)))
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
        audioEndingAt84Seconds().lastStart(Duration.ofMillis(-21)).end(Duration.ZERO).build();

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
        .lastStart(Duration.ofMillis(63_958))
        .end(Duration.ofSeconds(64))
        .lastKeyframeStart(Optional.of(Duration.ofSeconds(60)));
  }

  // Audio whose last frame starts inside segment 13, the last of 14.
  private static ObservedSourceStream.ObservedSourceStreamBuilder audioEndingAt84Seconds() {
    return ObservedSourceStream.builder()
        .sourceIndex(AUDIO_INDEX)
        .lastStart(Duration.ofMillis(83_989))
        .end(Duration.ofSeconds(84))
        .lastKeyframeStart(Optional.of(Duration.ofMillis(83_989)));
  }

  // 14 segments of 6 s: the last advertised segment, 13, runs from 78 s to 84 s.
  private static SourceEndRule ruleFor(VideoDecision video) {
    return SourceEndRule.builder().periodSeconds(6).mediaSegmentCount(14).video(video).build();
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
