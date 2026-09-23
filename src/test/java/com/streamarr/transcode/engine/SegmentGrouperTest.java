package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
class SegmentGrouperTest {

  private static final int PERIOD_SECONDS = 6;
  private static final long MILLISECONDS = 1000;

  @Test
  @DisplayName("Should deliver nothing when the first keyframe opens a segment")
  void shouldDeliverNothingWhenTheFirstKeyframeOpensASegment() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);

    assertThat(grouper.accept(keyframeAt(0))).isEmpty();
  }

  @Test
  @DisplayName("Should close the open segment when a keyframe starts inside the next interval")
  void shouldCloseTheOpenSegmentWhenAKeyframeStartsInsideTheNextInterval() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);
    var first = keyframeAt(0);
    var second = nonSyncAt(1_001);

    grouper.accept(first);
    grouper.accept(second);

    assertThat(grouper.accept(keyframeAt(6_006))).contains(segment(0, first, second));
  }

  @Test
  @DisplayName(
      "Should keep a second keyframe in the open segment when it starts in the same interval")
  void shouldKeepASecondKeyframeInTheOpenSegmentWhenItStartsInTheSameInterval() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);
    var timescale = 24_000L;
    var first = keyframeAt(0, timescale);
    var gopKeyframe = keyframeAt(143_143, timescale);

    grouper.accept(first);

    assertThat(grouper.accept(gopKeyframe)).isEmpty();
    assertThat(grouper.accept(keyframeAt(144_144, timescale)))
        .contains(segment(0, first, gopKeyframe));
  }

  @Test
  @DisplayName(
      "Should keep a non-sync fragment in the open segment when it starts past the interval")
  void shouldKeepANonSyncFragmentInTheOpenSegmentWhenItStartsPastTheInterval() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);
    var keyframe = keyframeAt(0);
    var lateNonSync = nonSyncAt(7_000);

    grouper.accept(keyframe);

    assertThat(grouper.accept(lateNonSync)).isEmpty();
    assertThat(grouper.accept(keyframeAt(7_500))).contains(segment(0, keyframe, lateNonSync));
  }

  @Test
  @DisplayName("Should keep video-less fragments in the open segment when they follow the video")
  void shouldKeepVideoLessFragmentsInTheOpenSegmentWhenTheyFollowTheVideo() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);
    var keyframe = keyframeAt(0);
    var firstAudio = audioOnly();
    var secondAudio = audioOnly();

    grouper.accept(keyframe);
    grouper.accept(firstAudio);
    grouper.accept(secondAudio);

    assertThat(grouper.finish()).contains(segment(0, keyframe, firstAudio, secondAudio));
  }

  @Test
  @DisplayName("Should close the open segment when the stream ends")
  void shouldCloseTheOpenSegmentWhenTheStreamEnds() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);
    var first = keyframeAt(0);
    var second = keyframeAt(6_000);
    var third = nonSyncAt(7_000);

    grouper.accept(first);
    var closed = grouper.accept(second);
    grouper.accept(third);

    assertThat(closed).contains(segment(0, first));
    assertThat(grouper.finish()).contains(segment(1, second, third));
  }

  @Test
  @DisplayName("Should deliver nothing when the stream ends before any segment opens")
  void shouldDeliverNothingWhenTheStreamEndsBeforeAnySegmentOpens() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);

    grouper.accept(audioOnly());

    assertThat(grouper.finish()).isEmpty();
  }

  @Test
  @DisplayName("Should add fragments that arrive before any segment opens to the first segment")
  void shouldAddFragmentsThatArriveBeforeAnySegmentOpensToTheFirstSegment() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);
    var audio = audioOnly();
    var nonSync = nonSyncAt(0);
    var keyframe = keyframeAt(40);

    grouper.accept(audio);
    grouper.accept(nonSync);
    grouper.accept(keyframe);

    assertThat(grouper.finish()).contains(segment(0, audio, nonSync, keyframe));
  }

  @Test
  @DisplayName("Should open the start sequence number when the first keyframe lies in its interval")
  void shouldOpenTheStartSequenceNumberWhenTheFirstKeyframeLiesInItsInterval() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 5);
    var keyframe = keyframeAt(30_000);

    grouper.accept(keyframe);

    assertThat(grouper.accept(keyframeAt(36_000))).contains(segment(5, keyframe));
  }

  @Test
  @DisplayName("Should discard preroll and its following fragments when a later keyframe opens")
  void shouldDiscardPrerollAndItsFollowingFragmentsWhenALaterKeyframeOpens() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 5);
    var startKeyframe = keyframeAt(30_030);

    assertThat(grouper.accept(keyframeAt(27_000))).isEmpty();
    assertThat(grouper.accept(nonSyncAt(28_000))).isEmpty();
    assertThat(grouper.accept(audioOnly())).isEmpty();
    assertThat(grouper.accept(startKeyframe)).isEmpty();
    assertThat(grouper.accept(keyframeAt(36_000))).contains(segment(5, startKeyframe));
  }

  @Test
  @DisplayName("Should discard every preroll segment when the preroll spans several intervals")
  void shouldDiscardEveryPrerollSegmentWhenThePrerollSpansSeveralIntervals() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 5);
    var startKeyframe = keyframeAt(30_000);

    assertThat(grouper.accept(keyframeAt(9_000))).isEmpty();
    assertThat(grouper.accept(keyframeAt(21_000))).isEmpty();
    assertThat(grouper.accept(keyframeAt(27_000))).isEmpty();
    assertThat(grouper.accept(startKeyframe)).isEmpty();
    assertThat(grouper.finish()).contains(segment(5, startKeyframe));
  }

  @Test
  @DisplayName("Should discard fragments waiting for a segment when the first segment is preroll")
  void shouldDiscardFragmentsWaitingForASegmentWhenTheFirstSegmentIsPreroll() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 5);
    var startKeyframe = keyframeAt(30_000);

    grouper.accept(audioOnly());
    grouper.accept(keyframeAt(27_000));
    grouper.accept(startKeyframe);

    assertThat(grouper.finish()).contains(segment(5, startKeyframe));
  }

  @Test
  @DisplayName("Should discard the preroll when the stream ends before the start sequence number")
  void shouldDiscardThePrerollWhenTheStreamEndsBeforeTheStartSequenceNumber() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 5);

    grouper.accept(keyframeAt(27_000));
    grouper.accept(nonSyncAt(28_000));

    assertThat(grouper.finish()).isEmpty();
  }

  @Test
  @DisplayName("Should place a negative presentation time in the interval below zero")
  void shouldPlaceANegativePresentationTimeInTheIntervalBelowZero() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);
    var zero = keyframeAt(0);

    assertThat(grouper.accept(keyframeAt(-6_001))).isEmpty();
    assertThat(grouper.accept(keyframeAt(-1))).isEmpty();
    assertThat(grouper.accept(zero)).isEmpty();
    assertThat(grouper.finish()).contains(segment(0, zero));
  }

  @Test
  @DisplayName("Should fail when a keyframe skips a segment number")
  void shouldFailWhenAKeyframeSkipsASegmentNumber() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);
    grouper.accept(keyframeAt(0));
    var skipping = keyframeAt(12_000);

    assertFailure(() -> grouper.accept(skipping), Reason.SKIPPED_SEGMENT_NUMBER);
  }

  @Test
  @DisplayName("Should fail when the first keyframe lies past the start sequence number")
  void shouldFailWhenTheFirstKeyframeLiesPastTheStartSequenceNumber() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 5);
    var skipping = keyframeAt(36_000);

    assertFailure(() -> grouper.accept(skipping), Reason.SKIPPED_SEGMENT_NUMBER);
  }

  @Test
  @DisplayName("Should fail when the preroll's next keyframe lies past the start sequence number")
  void shouldFailWhenThePrerollsNextKeyframeLiesPastTheStartSequenceNumber() {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 5);
    grouper.accept(keyframeAt(27_000));
    var skipping = keyframeAt(36_000);

    assertFailure(() -> grouper.accept(skipping), Reason.SKIPPED_SEGMENT_NUMBER);
  }

  @ParameterizedTest
  @CsvSource({"6000, 5999", "3000, 2000"})
  @DisplayName("Should fail when a keyframe starts before the previous keyframe")
  void shouldFailWhenAKeyframeStartsBeforeThePreviousKeyframe(long previous, long next) {
    var grouper = new SegmentGrouper(PERIOD_SECONDS, 0);
    grouper.accept(keyframeAt(0));
    grouper.accept(keyframeAt(previous));
    var earlier = keyframeAt(next);

    assertFailure(() -> grouper.accept(earlier), Reason.PRESENTATION_TIME_REGRESSED);
  }

  @Test
  @DisplayName("Should report a media segment's byte length when it holds several fragments")
  void shouldReportAMediaSegmentsByteLengthWhenItHoldsSeveralFragments() {
    var segment =
        new MediaSegment(
            0,
            List.of(
                new Fragment(List.of(new byte[40], new byte[100]), Optional.empty()),
                new Fragment(List.of(new byte[30], new byte[7]), Optional.empty())));

    assertThat(segment.byteLength()).isEqualTo(177);
  }

  @ParameterizedTest
  @CsvSource({"0, 0", "-6, 0", "6, -1"})
  @DisplayName("Should reject a grouping when its period or start sequence number cannot exist")
  void shouldRejectAGroupingWhenItsPeriodOrStartSequenceNumberCannotExist(
      int periodSeconds, int startSequenceNumber) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SegmentGrouper(periodSeconds, startSequenceNumber));
  }

  private static void assertFailure(Runnable accept, Reason reason) {
    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(accept::run)
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(reason);
  }

  private static MediaSegment segment(int sequenceNumber, Fragment... fragments) {
    return new MediaSegment(sequenceNumber, List.of(fragments));
  }

  private static Fragment keyframeAt(long milliseconds) {
    return keyframeAt(milliseconds, MILLISECONDS);
  }

  private static Fragment keyframeAt(long presentationTime, long timescale) {
    return fragment(Optional.of(new VideoStart(presentationTime, timescale, true)));
  }

  private static Fragment nonSyncAt(long milliseconds) {
    return fragment(Optional.of(new VideoStart(milliseconds, MILLISECONDS, false)));
  }

  private static Fragment audioOnly() {
    return fragment(Optional.empty());
  }

  private static Fragment fragment(Optional<VideoStart> videoStart) {
    return new Fragment(List.of(new byte[] {1}, new byte[] {2}), videoStart);
  }
}
