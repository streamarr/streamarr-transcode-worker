package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Required Component Tests")
class RequiredComponentTest {

  private static final MovieTracks NO_TRACKS = new MovieTracks(Optional.empty(), Optional.empty());
  private static final Fragment EMPTY_FRAGMENT =
      Fragment.builder()
          .boxes(List.of())
          .tracks(NO_TRACKS)
          .videoSamples(List.of())
          .audioSamples(List.of())
          .build();
  private static final Sample SAMPLE = Sample.builder().duration(1).size(1).build();

  @ParameterizedTest(name = "{0}")
  @MethodSource("valuesLackingARequiredComponent")
  @DisplayName("Should refuse to build a segment value when it lacks a required component")
  void shouldRefuseToBuildASegmentValueWhenItLacksARequiredComponent(
      String component, ThrowingCallable building) {
    assertThatNullPointerException().isThrownBy(building).withMessageContaining(component);
  }

  static Stream<Arguments> valuesLackingARequiredComponent() {
    return Stream.of(
        Arguments.of("boxes", (ThrowingCallable) () -> fragment().boxes(null).build()),
        Arguments.of("tracks", (ThrowingCallable) () -> fragment().tracks(null).build()),
        Arguments.of(
            "videoSamples", (ThrowingCallable) () -> fragment().videoSamples(null).build()),
        Arguments.of(
            "audioSamples", (ThrowingCallable) () -> fragment().audioSamples(null).build()),
        Arguments.of("video", (ThrowingCallable) () -> new MovieTracks(null, Optional.empty())),
        Arguments.of("audio", (ThrowingCallable) () -> new MovieTracks(Optional.empty(), null)),
        Arguments.of("fragment", (ThrowingCallable) () -> new CarriedSample(null, SAMPLE)),
        Arguments.of("sample", (ThrowingCallable) () -> new CarriedSample(EMPTY_FRAGMENT, null)),
        Arguments.of("segment", (ThrowingCallable) () -> new ClosedSegment.Initialization(null)),
        Arguments.of("fragments", (ThrowingCallable) () -> new MediaSegment(0, null, List.of())),
        Arguments.of("videoSamples", (ThrowingCallable) () -> new MediaSegment(0, List.of(), null)),
        Arguments.of("segments", (ThrowingCallable) () -> new GroupingOutcome.SegmentsClosed(null)),
        Arguments.of(
            "closedSegments",
            (ThrowingCallable) () -> new GroupingOutcome.SegmentNumberSkipped(null, 1, 2)),
        Arguments.of(
            "videoSamples", (ThrowingCallable) () -> rebuiltSegment().videoSamples(null).build()),
        Arguments.of(
            "audioSamples", (ThrowingCallable) () -> rebuiltSegment().audioSamples(null).build()),
        Arguments.of(
            "stillHeld", (ThrowingCallable) () -> rebuiltSegment().stillHeld(null).build()),
        Arguments.of("samples", (ThrowingCallable) () -> new MovieFragmentWriter.Track(1, null)),
        Arguments.of("samples", (ThrowingCallable) () -> new TrackSamples(1, null)),
        Arguments.of("trak", (ThrowingCallable) () -> new TrackBox(null)),
        Arguments.of("parts", (ThrowingCallable) () -> ProducedSegment.Media.of(0, null)),
        Arguments.of(
            "closedSegments",
            (ThrowingCallable) () -> new SegmentAssembler.Closing(null, Optional.empty())),
        Arguments.of(
            "failure", (ThrowingCallable) () -> new SegmentAssembler.Closing(List.of(), null)));
  }

  private static Fragment.FragmentBuilder fragment() {
    return Fragment.builder()
        .boxes(List.of())
        .tracks(NO_TRACKS)
        .videoSamples(List.of())
        .audioSamples(List.of());
  }

  private static RebuiltSegment.RebuiltSegmentBuilder rebuiltSegment() {
    return RebuiltSegment.builder()
        .sequenceNumber(0)
        .videoSamples(List.of())
        .audioSamples(List.of())
        .stillHeld(Set.of());
  }
}
