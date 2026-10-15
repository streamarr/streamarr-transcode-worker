package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Keyframe Positions Report Tests")
class KeyframePositionsReportTest {

  static Stream<Arguments> missingComponents() {
    return Stream.of(
        missing("probed", builder -> builder.probed(null)),
        missing("timeBase", builder -> builder.timeBase(null)),
        missing("source", builder -> builder.source(null)),
        missing("keyframes", builder -> builder.keyframes(null)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("missingComponents")
  @DisplayName("Should reject the report when a required component is missing")
  void shouldRejectReportWhenRequiredComponentIsMissing(
      String component,
      UnaryOperator<KeyframePositionsReport.KeyframePositionsReportBuilder> omit) {
    var builder = completeReport();

    assertThatNullPointerException()
        .isThrownBy(() -> omit.apply(builder))
        .withMessageContaining(component);
  }

  private static Arguments missing(
      String component,
      UnaryOperator<KeyframePositionsReport.KeyframePositionsReportBuilder> omit) {
    return Arguments.of(component, omit);
  }

  private static KeyframePositionsReport.KeyframePositionsReportBuilder completeReport() {
    return KeyframePositionsReport.builder()
        .probed(
            ProbedSource.builder()
                .source(Path.of("/media/movie.ts"))
                .ffprobeOutput(new ObjectMapper().createObjectNode())
                .result(ProbeAttemptResult.getDefaultInstance())
                .build())
        .streamIndex(0)
        .timeBase(new TimeBase(1, 90_000))
        .source(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN)
        .keyframes(List.of());
  }
}
