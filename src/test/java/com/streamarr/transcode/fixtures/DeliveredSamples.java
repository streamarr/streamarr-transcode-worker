package com.streamarr.transcode.fixtures;

import static com.streamarr.transcode.engine.FfmpegRecordings.assertBeginsWithTheDeliveredInitializationSegment;
import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.engine.FfmpegRecordings.SegmentSummary;
import com.streamarr.transcode.fixtures.Mp4Samples.Movie;
import com.streamarr.transcode.fixtures.Mp4Samples.Sample;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** What a recording's delivered segments must carry, read with {@link Mp4Samples}. */
public final class DeliveredSamples {

  private DeliveredSamples() {}

  /**
   * Asserts that the delivered output begins with the initialization segment {@code expected.json}
   * digests, that every sample it carries is the recording's sample of the same track and decode
   * time, bytes included, and that its video samples are those {@code expected.json} groups into
   * delivered segments, in order.
   */
  public static void assertCarriesTheRecordedSamples(byte[] delivered, Recording recording) {
    var recordedBytes = bytesOf(recording.file());
    assertBeginsWithTheDeliveredInitializationSegment(delivered, recording);
    var recorded = Mp4Samples.read(recordedBytes);
    var deliveredMovie = Mp4Samples.read(delivered);
    var recordedSamples = byTrackAndDecodeTime(recorded);
    assertThat(allSamplesOf(deliveredMovie))
        .isNotEmpty()
        .allSatisfy(
            sample ->
                assertThat(sample)
                    .isEqualTo(
                        recordedSamples.get(List.of(sample.trackId(), sample.decodeTime()))));
    assertThat(deliveredMovie.samplesOf(deliveredMovie.videoTrack()))
        .containsExactlyElementsOf(videoSamplesOfTheDeliveredSegments(recording, recorded));
  }

  /**
   * The recording's audio samples that start inside the interval of a segment {@code expected.json}
   * delivers, in order: segment zero of an attempt from the start also takes the priming frame
   * before zero, and the last segment of a completed attempt every sample after its interval.
   */
  public static List<Sample> audioSamplesOfTheDeliveredSegments(
      Recording recording, boolean completed) {
    var recorded = Mp4Samples.read(bytesOf(recording.file()));
    var audio = recorded.audioTrack();
    var periodTicks = recording.period() * audio.timescale();
    var numbers = recording.segments().stream().map(SegmentSummary::number).toList();
    return recorded.samplesOf(audio).stream()
        .filter(
            sample -> {
              var number = Math.floorDiv(sample.presentationTime(), periodTicks);
              if (number < 0 && recording.startSequenceNumber() == 0) {
                number = 0;
              }

              return numbers.contains(Math.toIntExact(number))
                  || (completed && number > numbers.getLast());
            })
        .toList();
  }

  private static Map<List<Long>, Sample> byTrackAndDecodeTime(Movie movie) {
    return allSamplesOf(movie).stream()
        .collect(
            Collectors.toMap(
                sample -> List.of(sample.trackId(), sample.decodeTime()), Function.identity()));
  }

  private static List<Sample> allSamplesOf(Movie movie) {
    return movie.fragments().stream().flatMap(fragment -> fragment.samples().stream()).toList();
  }

  // The recorded video samples from the keyframe that opens the first segment expected.json
  // delivers to the end of its last segment's fragments.
  private static List<Sample> videoSamplesOfTheDeliveredSegments(
      Recording recording, Movie recorded) {
    var first = recording.segments().getFirst();
    var last = recording.segments().getLast();
    var videoTrackId = recorded.videoTrack().trackId();
    return recorded
        .fragments()
        .subList(first.firstFragmentIndex(), last.firstFragmentIndex() + last.fragmentCount())
        .stream()
        .flatMap(fragment -> fragment.samples().stream())
        .filter(sample -> sample.trackId() == videoTrackId)
        .dropWhile(sample -> sample.presentationTime() != first.firstVideoPresentationTime())
        .toList();
  }
}
