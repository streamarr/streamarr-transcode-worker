package com.streamarr.transcode.fixtures;

import static com.streamarr.transcode.engine.FfmpegRecordings.assertBeginsWithTheDeliveredInitializationSegment;
import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
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
   * time, bytes included, and that its video samples are those of the fragments {@code
   * expected.json} groups into delivered segments, in order.
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

  private static Map<List<Long>, Sample> byTrackAndDecodeTime(Movie movie) {
    return allSamplesOf(movie).stream()
        .collect(
            Collectors.toMap(
                sample -> List.of(sample.trackId(), sample.decodeTime()), Function.identity()));
  }

  private static List<Sample> allSamplesOf(Movie movie) {
    return movie.fragments().stream().flatMap(fragment -> fragment.samples().stream()).toList();
  }

  // The video samples of the recorded fragments that expected.json groups into delivered segments.
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
        .toList();
  }
}
