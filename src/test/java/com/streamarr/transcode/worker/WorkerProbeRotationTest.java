package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Worker Probe Rotation Tests")
@Timeout(30)
class WorkerProbeRotationTest {

  @TempDir Path sourceRoot;

  @ParameterizedTest
  @MethodSource("matrixRotations")
  @DisplayName("Should report the applied rotation when ffprobe truncates the display matrix angle")
  void shouldReportTheAppliedRotationWhenFfprobeTruncatesTheDisplayMatrixAngle(
      String displayMatrix, int reported, int applied) throws Exception {
    var video = probeRotation(displayMatrix, reported);

    assertThat(video.hasRotationDegrees()).isTrue();
    assertThat(video.getRotationDegrees()).isEqualTo(applied);
    assertThat(video.getWidth()).isEqualTo(320);
    assertThat(video.getHeight()).isEqualTo(180);
  }

  private static Stream<Arguments> matrixRotations() {
    return Stream.of(
        Arguments.of(matrix("65536 0 0", "0 65536 0"), 0, 0),
        Arguments.of(matrix("46340 -46340 0", "46340 46340 0"), 45, 45),
        Arguments.of(matrix("1029 -65527 0", "65527 1029 0"), 89, 89),
        Arguments.of(matrix("1029 65527 0", "-65527 1029 0"), -89, -89),
        Arguments.of(matrix("571 -65533 0", "65533 571 0"), 89, 90),
        Arguments.of(matrix("571 65533 0", "-65533 571 0"), -89, -90),
        Arguments.of(matrix("0 -65536 0", "65536 0 0"), 90, 90),
        Arguments.of(matrix("0 65536 0", "-65536 0 0"), -90, -90),
        Arguments.of(matrix("-1029 -65527 0", "65527 -1029 0"), 90, 91),
        Arguments.of(matrix("-1029 65527 0", "-65527 -1029 0"), -90, -91),
        Arguments.of(matrix("-1143 -65526 0", "65526 -1143 0"), 90, 91),
        Arguments.of(matrix("-1143 65526 0", "-65526 -1143 0"), -90, -91),
        Arguments.of(matrix("-65533 -571 0", "571 -65533 0"), 179, 180),
        Arguments.of(matrix("-65533 571 0", "-571 -65533 0"), -179, -180),
        Arguments.of(matrix("8285927 -949473190 0", "949473190 8285927 0"), 89, 90),
        Arguments.of(matrix("8285927 949473190 0", "-949473190 8285927 0"), -89, -90),
        Arguments.of(matrix("-571 -65533 0", "-65533 571 0"), 90, 90),
        Arguments.of(matrix("571 65533 0", "65533 -571 0"), -89, -90),
        Arguments.of(matrix("-571 65533 0", "-65533 -571 0"), -90, -90),
        Arguments.of(matrix("9136 -65533 0", "1048528 571 0"), 89, 90),
        Arguments.of(matrix("571 -1048528 0", "65533 9136 0"), 89, 90));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "00000000: 0 -65536 0", "unreadable matrix"})
  @MethodSource("unreadableMatrices")
  @DisplayName("Should keep the reported rotation when the display matrix cannot be read")
  void shouldKeepTheReportedRotationWhenTheDisplayMatrixCannotBeRead(String displayMatrix)
      throws Exception {
    var video = probeRotation(displayMatrix, 0);

    assertThat(video.hasRotationDegrees()).isTrue();
    assertThat(video.getRotationDegrees()).isZero();
  }

  private static Stream<String> unreadableMatrices() {
    return Stream.of(
        matrix("0 -65536", "65536 0 0"),
        matrix("not-a-number -65536 0", "65536 0 0"),
        matrix("2147483648 -65536 0", "65536 0 0"),
        matrix("0 -65536 0", "0 0 0"),
        matrix("65536 0 0", "0 0 0"),
        "0 -65536 0\n00000001: 65536 0 0\n00000002: 0 0 1073741824");
  }

  private static String matrix(String firstRow, String secondRow) {
    return """
        00000000: %s
        00000001: %s
        00000002: 0 0 1073741824
        """
        .formatted(firstRow, secondRow);
  }

  private ProbeStreamInfo probeRotation(String displayMatrix, int reported) throws Exception {
    return probeOutput(
        """
        {"streams":[{"index":0,"codec_type":"video","time_base":"1/24",
          "r_frame_rate":"24/1","avg_frame_rate":"24/1","width":320,"height":180,
          "side_data_list":[{"side_data_type":"Display Matrix",
            "displaymatrix":%s,"rotation":%d}]}]}
        """
            .formatted(new ObjectMapper().writeValueAsString(displayMatrix), reported));
  }

  private ProbeStreamInfo probeOutput(String output) throws Exception {
    Files.writeString(sourceRoot.resolve("movie.mkv"), output);
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = workerBuilder(sourceRoot).runtime(runtime).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), requestBuilder().build());
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(result -> assertThat(result.hasMedia()).isTrue());
      return runtime.connection().results().getFirst().getMedia().getStreams(0);
    }
  }
}
