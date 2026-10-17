package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Segment Assembler Tests")
class SegmentAssemblerTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("assemblersLackingARequiredArgument")
  @DisplayName("Should refuse to build an assembler when it lacks a required argument")
  void shouldRefuseToBuildAnAssemblerWhenItLacksARequiredArgument(
      String argument, SegmentAssembler.SegmentAssemblerBuilder builder) {
    assertThatNullPointerException().isThrownBy(builder::build).withMessageContaining(argument);
  }

  // The builder's setters refuse null themselves, so each assembler leaves its argument unset.
  static Stream<Arguments> assemblersLackingARequiredArgument() {
    return Stream.of(
        Arguments.of(
            "encodedFrameRate",
            assembler().videoDecision(VideoDecisionFixtures.encodeBuilder().build())),
        Arguments.of(
            "videoDecision", assembler().encodedFrameRate(Optional.<OutputFrameRate>empty())));
  }

  private static SegmentAssembler.SegmentAssemblerBuilder assembler() {
    return SegmentAssembler.builder()
        .periodSeconds(6)
        .startSequenceNumber(0)
        .maximumSegmentBytes(1024);
  }
}
