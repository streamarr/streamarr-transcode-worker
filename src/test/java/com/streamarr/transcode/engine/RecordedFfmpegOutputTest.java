package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_DATA_OFFSET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.streamarr.transcode.engine.FfmpegRecordings.CutPoint;
import com.streamarr.transcode.engine.FfmpegRecordings.CutPointMismatch;
import com.streamarr.transcode.engine.FfmpegRecordings.ExpectedFailure;
import com.streamarr.transcode.engine.FfmpegRecordings.HlsRun;
import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.engine.FfmpegRecordings.SegmentSummary;
import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.GroupingOutcome.NothingClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentNumberSkipped;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
class RecordedFfmpegOutputTest {

  static Stream<Recording> recordings() {
    return FfmpegRecordings.recordings().stream();
  }

  static Stream<Recording> recordingsThatGroupWithoutFailure() {
    return recordings().filter(recording -> recording.failure().isEmpty());
  }

  static Stream<Recording> recordingsEndingInAudioOnlyFragments() {
    return recordingsThatGroupWithoutFailure()
        .filter(recording -> recording.trailingAudioOnlyFragmentCount() > 0);
  }

  static Stream<Arguments> hlsRuns() {
    return recordings()
        .flatMap(
            recording ->
                recording.hlsOracles().stream().map(hlsRun -> Arguments.of(recording, hlsRun)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  @DisplayName("Should reproduce the recording when concatenating every unit the reader returns")
  void shouldReproduceTheRecordingWhenConcatenatingEveryUnitTheReaderReturns(Recording recording)
      throws IOException {
    var units = read(recording.file());
    var fragmentBytes = units.fragments().stream().flatMap(fragment -> fragment.boxes().stream());

    assertThat(units.initializationSegment().bytes())
        .hasSize(recording.initializationSegment().byteLength());
    assertThat(
            concat(Stream.concat(Stream.of(units.initializationSegment().bytes()), fragmentBytes)))
        .isEqualTo(bytesOf(recording.file()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  @DisplayName("Should deliver the recorded media segments when grouping each recording")
  void shouldDeliverTheRecordedMediaSegmentsWhenGroupingEachRecording(Recording recording)
      throws IOException {
    var grouping = group(recording);

    assertThat(summaries(grouping)).containsExactlyElementsOf(recording.segments());
    assertThat(grouping.failure()).isEqualTo(recording.failure());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordingsThatGroupWithoutFailure")
  @DisplayName("Should deliver every fragment after the preroll byte for byte when grouping")
  void shouldDeliverEveryFragmentAfterThePrerollByteForByteWhenGrouping(Recording recording)
      throws IOException {
    var recorded = bytesOf(recording.file());
    var firstDeliveredByte =
        recording.initializationSegment().byteLength()
            + recording.discardedPreroll().stream().mapToLong(SegmentSummary::byteLength).sum();
    var delivered =
        group(recording).delivered().stream()
            .flatMap(segment -> segment.fragments().stream())
            .flatMap(fragment -> fragment.boxes().stream());

    assertThat(concat(delivered))
        .isEqualTo(
            Arrays.copyOfRange(recorded, Math.toIntExact(firstDeliveredByte), recorded.length));
  }

  @ParameterizedTest(name = "{0} against {1}")
  @MethodSource("hlsRuns")
  @DisplayName(
      "Should differ from the HLS muxer's cut points only on the recorded segments when grouping on"
          + " the grid")
  void shouldDifferFromTheHlsMuxersCutPointsOnlyOnTheRecordedSegmentsWhenGroupingOnTheGrid(
      Recording recording, HlsRun hlsRun) throws IOException {
    assertThat(mismatches(cutPoints(group(recording)), hlsRun.segments()))
        .containsExactlyElementsOf(hlsRun.mismatches());
  }

  @Test
  @DisplayName(
      "Should discard the preroll when a stream-copy replacement attempt lands on a keyframe before"
          + " its first segment")
  void
      shouldDiscardThePrerollWhenAStreamCopyReplacementAttemptLandsOnAKeyframeBeforeItsFirstSegment()
          throws IOException {
    var grouping = group(recording("07-copy-seek30.fmp4"));
    var firstDelivered = grouping.delivered().getFirst();

    assertThat(grouping.units().fragments().getFirst().videoStart())
        .contains(new VideoStart(672_672, 24_000, true));
    assertThat(grouping.units().indexOf(firstDelivered.fragments().getFirst())).isEqualTo(2);
    assertThat(firstDelivered.sequenceNumber()).isEqualTo(5);
    assertThat(firstVideoPresentationTime(firstDelivered)).isEqualTo(720_720);
    assertThat(cutPoints(grouping))
        .containsExactlyElementsOf(
            cutPoints(group(recording("07-copy-start0.fmp4"))).subList(5, 11));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordingsEndingInAudioOnlyFragments")
  @DisplayName(
      "Should keep the audio-only fragments in the last media segment when audio outlasts the"
          + " video")
  void shouldKeepTheAudioOnlyFragmentsInTheLastMediaSegmentWhenAudioOutlastsTheVideo(
      Recording recording) throws IOException {
    var grouping = group(recording);
    var lastSegment = grouping.delivered().getLast().fragments();
    var audioOnly =
        lastSegment.subList(
            lastSegment.size() - recording.trailingAudioOnlyFragmentCount(), lastSegment.size());

    assertThat(audioOnly).allSatisfy(fragment -> assertThat(fragment.videoStart()).isEmpty());
    assertThat(lastSegment.getLast()).isSameAs(grouping.units().fragments().getLast());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  @DisplayName("Should start every video fragment at or after media time zero when recorded")
  void shouldStartEveryVideoFragmentAtOrAfterMediaTimeZeroWhenRecorded(Recording recording)
      throws IOException {
    assertThat(read(recording.file()).fragments())
        .flatMap(fragment -> fragment.videoStart().stream().toList())
        .isNotEmpty()
        .allSatisfy(start -> assertThat(start.presentationTime()).isNotNegative());
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"05-encode-late-start.fmp4, 1001", "05-copy-late-start.fmp4, 1920"})
  @DisplayName("Should open media segment zero when the source's timestamps begin after zero")
  void shouldOpenMediaSegmentZeroWhenTheSourcesTimestampsBeginAfterZero(
      String file, long firstVideoPresentationTime) throws IOException {
    var first = group(recording(file)).delivered().getFirst();

    assertThat(first.sequenceNumber()).isZero();
    assertThat(firstVideoPresentationTime(first)).isEqualTo(firstVideoPresentationTime);
  }

  @ParameterizedTest(name = "{0} and {1}")
  @CsvSource({
    "01-encode-cfr.fmp4, 01-encode-cfr-seek30.fmp4",
    "07-copy-start0.fmp4, 07-copy-seek30.fmp4",
    "09-svtav1-vfr.fmp4, 09-svtav1-vfr-seek30.fmp4"
  })
  @DisplayName(
      "Should read identical initialization segments when a replacement attempt keeps the encoder"
          + " backend")
  void shouldReadIdenticalInitializationSegmentsWhenAReplacementAttemptKeepsTheEncoderBackend(
      String firstAttempt, String replacementAttempt) throws IOException {
    assertThat(read(replacementAttempt).initializationSegment())
        .isEqualTo(read(firstAttempt).initializationSegment());
  }

  @Test
  @DisplayName(
      "Should read different initialization segments when one source is encoded and stream copied")
  void shouldReadDifferentInitializationSegmentsWhenOneSourceIsEncodedAndStreamCopied()
      throws IOException {
    assertThat(read("07-copy-start0.fmp4").initializationSegment())
        .isNotEqualTo(read("01-encode-cfr.fmp4").initializationSegment());
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"01-encode-cfr-seek30.fmp4, 720720", "09-svtav1-vfr-seek30.fmp4, 721721"})
  @DisplayName(
      "Should deliver its first fragment at the seek point when an encoded attempt seeks under the"
          + " frame-rate flags")
  void shouldDeliverItsFirstFragmentAtTheSeekPointWhenAnEncodedAttemptSeeksUnderTheFrameRateFlags(
      String file, long seekPoint) throws IOException {
    var grouping = group(recording(file));
    var firstFragment = grouping.units().fragments().getFirst();

    assertThat(firstFragment.videoStart()).contains(new VideoStart(seekPoint, 24_000, true));
    assertThat(grouping.delivered().getFirst().fragments().getFirst()).isSameAs(firstFragment);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"09-svtav1-vfr.fmp4, 0", "09-svtav1-vfr-seek30.fmp4, 5"})
  @DisplayName(
      "Should deliver every interval when SVT-AV1 encodes a variable-frame-rate source under a"
          + " frame-count GOP")
  void shouldDeliverEveryIntervalWhenSvtAv1EncodesAVariableFrameRateSourceUnderAFrameCountGop(
      String file, int firstSegment) throws IOException {
    var grouping = group(recording(file));

    assertThat(grouping.failure()).isEmpty();
    assertThat(grouping.delivered())
        .extracting(MediaSegment::sequenceNumber)
        .containsExactlyElementsOf(IntStream.rangeClosed(firstSegment, 10).boxed().toList());
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "01-encode-cfr.fmp4",
    "01-encode-cfr-seek30.fmp4",
    "03-encode-vfr.fmp4",
    "09-svtav1-vfr.fmp4",
    "09-svtav1-vfr-seek30.fmp4"
  })
  @DisplayName(
      "Should start every segment with one keyframe when a verified encoder's GOP backstop exceeds"
          + " the period")
  void shouldStartEverySegmentWithOneKeyframeWhenAVerifiedEncodersGopBackstopExceedsThePeriod(
      String file) throws IOException {
    assertThat(group(recording(file)).delivered())
        .isNotEmpty()
        .allSatisfy(segment -> assertThat(syncFirstFragmentCount(segment)).isOne());
  }

  @Test
  @DisplayName(
      "Should keep both keyframes in one segment when an unverified encoder's floored GOP fires one"
          + " frame early")
  void shouldKeepBothKeyframesInOneSegmentWhenAnUnverifiedEncodersFlooredGopFiresOneFrameEarly()
      throws IOException {
    var grouping = group(recording("11-encode-cfr-floored-gop.fmp4"));

    assertThat(grouping.delivered())
        .extracting(MediaSegment::sequenceNumber)
        .containsExactly(0, 1, 2, 3, 4);
    assertThat(grouping.delivered())
        .allSatisfy(segment -> assertThat(syncFirstFragmentCount(segment)).isEqualTo(2));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "12-encode-cfr-missed-forced-keyframe.fmp4",
    "12-svtav1-cfr-missed-forced-keyframe.fmp4"
  })
  @DisplayName(
      "Should open a missed forced keyframe's interval at the GOP backstop when a verified encoder"
          + " misses one")
  void shouldOpenAMissedForcedKeyframesIntervalAtTheGopBackstopWhenAVerifiedEncoderMissesOne(
      String file) throws IOException {
    var grouping = group(recording(file));

    assertThat(grouping.failure()).isEmpty();
    assertThat(cutPoints(grouping)).containsExactlyElementsOf(backstopCutPoints());
  }

  @Test
  @DisplayName(
      "Should open a missed forced keyframe's interval at the GOP backstop when unverified libx265"
          + " runs under the verified GOP")
  void shouldOpenAMissedForcedKeyframesIntervalAtTheGopBackstopWhenUnverifiedLibx265RunsUnderIt()
      throws IOException {
    var grouping = group(recording("13-x265-cfr-missed-forced-keyframe.fmp4"));

    assertThat(grouping.failure()).isEmpty();
    assertThat(cutPoints(grouping)).containsExactlyElementsOf(backstopCutPoints());
  }

  @Test
  @DisplayName(
      "Should deliver the segment the skipping keyframe closed, then fail, when source keyframes are"
          + " further apart than the period")
  void
      shouldDeliverTheSegmentTheSkippingKeyframeClosedThenFailWhenSourceKeyframesAreFurtherApartThanThePeriod()
          throws IOException {
    var units = read("10-copy-gop-exceeds-period.fmp4");
    var grouper = new SegmentGrouper(6, 0, Mp4Stream.SEGMENT_CAP);
    var outcomes = units.fragments().subList(0, 21).stream().map(grouper::accept).toList();
    var segmentOne = new MediaSegment(1, units.fragments().subList(10, 20));

    assertThat(outcomes.get(10))
        .isEqualTo(new SegmentClosed(new MediaSegment(0, units.fragments().subList(0, 10))));
    assertThat(outcomes.subList(11, 20)).allMatch(NothingClosed.class::isInstance);
    assertThat(outcomes.getLast())
        .isEqualTo(new SegmentNumberSkipped(Optional.of(segmentOne), 2, 3));
    assertThat(firstVideoPresentationTime(segmentOne)).isEqualTo(240_240);
  }

  @Test
  @DisplayName("Should fail with the segment cap when a recorded media segment outgrows the cap")
  void shouldFailWithTheSegmentCapWhenARecordedMediaSegmentOutgrowsTheCap() throws IOException {
    var recording = recording("01-encode-cfr.fmp4");
    var largestSegment =
        recording.segments().stream().mapToLong(SegmentSummary::byteLength).max().orElseThrow();

    assertThat(group(recording, largestSegment).failure()).isEmpty();
    assertThat(group(recording, largestSegment - 1).failure())
        .map(ExpectedFailure::reason)
        .contains(Reason.EXCEEDS_SEGMENT_CAP);
  }

  @Test
  @DisplayName(
      "Should fail as a malformed box when a recorded trun declares one sample more than it holds")
  void shouldFailAsAMalformedBoxWhenARecordedTrunDeclaresOneSampleMoreThanItHolds() {
    var recorded = bytesOf("01-encode-cfr.fmp4");
    var fields = ByteBuffer.wrap(recorded);
    var sampleCount = positionOf(recorded, "trun") + 8;
    assertThat(fields.getInt(sampleCount)).isEqualTo(24);
    fields.putInt(sampleCount, 25);

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> Mp4Stream.read(Mp4Stream.readerOf(recorded)))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.MALFORMED_BOX);
  }

  @ParameterizedTest(name = "trun {0} at data offset {1}")
  @CsvSource({"0, 0", "1, 1000000"})
  @DisplayName(
      "Should fail with sample data outside the mdat when a recorded trun points outside its"
          + " fragment's mdat")
  void shouldFailWithSampleDataOutsideTheMdatWhenARecordedTrunPointsOutsideItsFragmentsMdat(
      int occurrence, int dataOffset) {
    var recorded = bytesOf("01-encode-cfr.fmp4");
    var fields = ByteBuffer.wrap(recorded);
    var run = positionOf(recorded, "trun", occurrence);
    assertThat(fields.getInt(run + 4) & TRUN_DATA_OFFSET).isEqualTo(TRUN_DATA_OFFSET);
    fields.putInt(run + 12, dataOffset);

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> Mp4Stream.read(Mp4Stream.readerOf(recorded)))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.SAMPLE_DATA_OUTSIDE_MDAT);
  }

  private static Mp4Stream read(String file) throws IOException {
    return Mp4Stream.read(Mp4Stream.readerOf(bytesOf(file)));
  }

  private static Grouping group(Recording recording) throws IOException {
    return group(recording, Mp4Stream.SEGMENT_CAP);
  }

  private static Grouping group(Recording recording, long maximumSegmentBytes) throws IOException {
    var units = read(recording.file());
    var grouper =
        new SegmentGrouper(
            recording.period(), recording.startSequenceNumber(), maximumSegmentBytes);
    var delivered = new ArrayList<MediaSegment>();
    for (var index = 0; index < units.fragments().size(); index++) {
      try {
        switch (grouper.accept(units.fragments().get(index))) {
          case NothingClosed _ -> {}
          case SegmentClosed(var segment) -> delivered.add(segment);
          case SegmentNumberSkipped skipped -> {
            skipped.closedSegment().ifPresent(delivered::add);
            return new Grouping(units, delivered, Optional.of(failureOf(skipped, index)));
          }
        }
      } catch (FragmentedMp4Exception e) {
        return new Grouping(units, delivered, Optional.of(failureOf(e, index)));
      }
    }

    grouper.finish().ifPresent(delivered::add);
    return new Grouping(units, delivered, Optional.empty());
  }

  private static List<SegmentSummary> summaries(Grouping grouping) {
    return grouping.delivered().stream()
        .map(
            segment ->
                SegmentSummary.builder()
                    .number(segment.sequenceNumber())
                    .firstVideoPresentationTime(firstVideoPresentationTime(segment))
                    .firstFragmentIndex(grouping.units().indexOf(segment.fragments().getFirst()))
                    .fragmentCount(segment.fragments().size())
                    .syncFirstFragmentCount(syncFirstFragmentCount(segment))
                    .byteLength(segment.byteLength())
                    .build())
        .toList();
  }

  /**
   * The segments of the first 30 s at 23.976 fps when the forced keyframe for 18 s is suppressed:
   * the GOP count restarts at the forced keyframe on frame 288, so its backstop keys frame 433.
   */
  private static List<CutPoint> backstopCutPoints() {
    var frame = 1001;
    return List.of(
        new CutPoint(0, 0),
        new CutPoint(1, 144L * frame),
        new CutPoint(2, 288L * frame),
        new CutPoint(3, (288L + 145) * frame),
        new CutPoint(4, 576L * frame));
  }

  private static List<CutPoint> cutPoints(Grouping grouping) {
    return grouping.delivered().stream()
        .map(segment -> new CutPoint(segment.sequenceNumber(), firstVideoPresentationTime(segment)))
        .toList();
  }

  private static List<CutPointMismatch> mismatches(List<CutPoint> grid, List<CutPoint> hls) {
    var gridStarts = startsByNumber(grid);
    var hlsStarts = startsByNumber(hls);
    return Stream.concat(gridStarts.keySet().stream(), hlsStarts.keySet().stream())
        .distinct()
        .sorted()
        .map(
            number ->
                new CutPointMismatch(
                    number,
                    Optional.ofNullable(gridStarts.get(number)),
                    Optional.ofNullable(hlsStarts.get(number))))
        .filter(mismatch -> !mismatch.grouping().equals(mismatch.hls()))
        .toList();
  }

  private static Map<Integer, Long> startsByNumber(List<CutPoint> cutPoints) {
    return cutPoints.stream()
        .collect(Collectors.toMap(CutPoint::number, CutPoint::firstVideoPresentationTime));
  }

  private static long firstVideoPresentationTime(MediaSegment segment) {
    return segment.fragments().stream()
        .flatMap(fragment -> fragment.videoStart().stream())
        .findFirst()
        .orElseThrow()
        .presentationTime();
  }

  private static int syncFirstFragmentCount(MediaSegment segment) {
    return Math.toIntExact(
        segment.fragments().stream()
            .filter(fragment -> fragment.videoStart().filter(VideoStart::syncSample).isPresent())
            .count());
  }

  private static ExpectedFailure failureOf(SegmentNumberSkipped skipped, int fragmentIndex) {
    return ExpectedFailure.builder()
        .reason(skipped.failure().getReason())
        .fragmentIndex(fragmentIndex)
        .expectedNumber(Optional.of(skipped.expectedNumber()))
        .actualNumber(Optional.of(skipped.actualNumber()))
        .build();
  }

  private static ExpectedFailure failureOf(FragmentedMp4Exception failure, int fragmentIndex) {
    return ExpectedFailure.builder()
        .reason(failure.getReason())
        .fragmentIndex(fragmentIndex)
        .expectedNumber(Optional.empty())
        .actualNumber(Optional.empty())
        .build();
  }

  /** The position of a box type's first four-character code; a full box's flags follow it. */
  private static int positionOf(byte[] bytes, String type) {
    return positionOf(bytes, type, 0);
  }

  /** The position of a box type's four-character code in its occurrence counted from 0. */
  private static int positionOf(byte[] bytes, String type, int occurrence) {
    var fourcc = type.getBytes(StandardCharsets.ISO_8859_1);
    return IntStream.range(0, bytes.length - fourcc.length)
        .filter(
            position ->
                Arrays.equals(bytes, position, position + fourcc.length, fourcc, 0, fourcc.length))
        .skip(occurrence)
        .findFirst()
        .orElseThrow();
  }

  private static byte[] concat(Stream<byte[]> parts) {
    var bytes = new ByteArrayOutputStream();
    parts.forEach(bytes::writeBytes);
    return bytes.toByteArray();
  }

  private record Grouping(
      Mp4Stream units, List<MediaSegment> delivered, Optional<ExpectedFailure> failure) {}
}
