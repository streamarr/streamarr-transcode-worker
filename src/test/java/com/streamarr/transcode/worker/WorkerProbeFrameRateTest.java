package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import build.buf.gen.streamarr.transcode.v1.Fraction;
import build.buf.gen.streamarr.transcode.v1.FrameRateRuleResult;
import build.buf.gen.streamarr.transcode.v1.OutputFrameRateBasis;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeCommand;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Worker Probe Frame Rate Tests")
class WorkerProbeFrameRateTest {

  @TempDir Path root;

  @Test
  @DisplayName("Should exclude slower intervals when the motion cadence forms a narrow cluster")
  void shouldExcludeSlowerIntervalsWhenTheMotionCadenceFormsANarrowCluster() throws Exception {
    // Eighty intervals of 40 ticks establish 1001/40 fps; twenty 48-tick gaps lie outside it.
    var rule =
        probe(
            video()
                .timeBase("1/1001")
                .packets(packets(intervals().repeat(40, 80).repeat(48, 20).build()))
                .build());

    assertThat(rule.getInputs().getPacketScan().getMotionRate()).isEqualTo(25.025);
    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(25, 1));
  }

  @Test
  @DisplayName("Should keep distinct cadences separate when a frame lasts only one timestamp tick")
  void shouldKeepDistinctCadencesSeparateWhenAFrameLastsOnlyOneTimestampTick() throws Exception {
    // One tick is 2/101 s: the one-tick cadence runs at 50.5 fps, not the two-tick cadence.
    var rule =
        probe(
            video()
                .timeBase("2/101")
                .packets(packets(intervals().repeat(1, 80).repeat(2, 20).build()))
                .build());

    assertThat(rule.getInputs().getPacketScan().getMotionRate()).isEqualTo(50.5);
    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(50, 1));
  }

  @ParameterizedTest(name = "{0} occurrences -> {1} fps")
  @CsvSource({"2, 24.531955790485343", "3, 25.00240746313572"})
  @DisplayName("Should require three recurrences when an interval changes the timestamp step")
  void shouldRequireThreeRecurrencesWhenAnIntervalChangesTheTimestampStep(
      int occurrences, double expectedMotionRate) throws Exception {
    // The recurring 40/44-tick intervals have step 4. A third 41-tick interval reduces it to 1,
    // excluding 44-tick gaps from the cluster. The expected means use 4,162 and 3,323 ticks.
    var rule =
        probe(
            video()
                .timeBase("1/1001")
                .packets(
                    packets(
                        intervals().repeat(40, 80).repeat(44, 20).repeat(41, occurrences).build()))
                .build());

    assertThat(rule.getInputs().getPacketScan().getMotionRate())
        .isCloseTo(expectedMotionRate, within(1e-12));
  }

  @ParameterizedTest(name = "3 of {0} intervals -> {1} fps")
  @CsvSource({"3000, 250.0171869629074", "3001, 245.1181447694029"})
  @DisplayName(
      "Should require a tenth of a percent when recurring intervals set the timestamp step")
  void shouldRequireATenthOfAPercentWhenRecurringIntervalsSetTheTimestampStep(
      int intervalCount, double expectedMotionRate) throws Exception {
    // Three 41-tick gaps qualify at 3,000 intervals, but not at 3,001. At the boundary the
    // cluster has 2,400 intervals over 96,003 ticks; below it all 3,001 span 122,443 ticks.
    // The 1/10001 time base keeps both scans under 13 seconds.
    var rule =
        probe(
            video()
                .timeBase("1/10001")
                .packets(
                    packets(
                        intervals()
                            .repeat(40, intervalCount - 603)
                            .repeat(44, 600)
                            .repeat(41, 3)
                            .build()))
                .build());

    assertThat(rule.getInputs().getPacketScan().getMotionRate())
        .isCloseTo(expectedMotionRate, within(1e-10));
  }

  @ParameterizedTest(name = "{0}% fast intervals -> {1} fps")
  @CsvSource({"9, 25.025", "11, 50.05"})
  @DisplayName("Should choose the ninetieth percentile when the scan contains two motion cadences")
  void shouldChooseTheNinetiethPercentileWhenTheScanContainsTwoMotionCadences(
      int fastIntervals, double expectedMotionRate) throws Exception {
    // The slower 40-tick cadence is always the mode and median. The 20-tick cadence becomes
    // the 90th percentile only when it accounts for more than 10% of the intervals.
    var rule =
        probe(
            video()
                .timeBase("1/1001")
                .packets(
                    packets(
                        intervals()
                            .repeat(20, fastIntervals)
                            .repeat(40, 100 - fastIntervals)
                            .build()))
                .build());

    assertThat(rule.getInputs().getPacketScan().getMotionRate()).isEqualTo(expectedMotionRate);
  }

  @Test
  @DisplayName("Should preserve a keyframe when it follows a packet at a field-pair interval")
  void shouldPreserveAKeyframeWhenItFollowsAPacketAtAFieldPairInterval() throws Exception {
    var rule =
        probe(
            video()
                .averageRate("25/1")
                .fieldOrder("tt")
                .timeBase("1/90000")
                .packets(
                    """
                    0,0,100,K__,
                    1800,1800,100,___,
                    3600,3600,100,___,
                    5400,5400,100,K__,
                    9000,9000,100,___,
                    10800,10800,100,___,
                    12600,12600,100,___,
                    """)
                .build());

    // The two true field pairs become frames at 0 and 9000. The whole frame at 3600 and
    // keyframe at 5400 remain separate, even though their gap is also half a frame.
    assertThat(rule.getInputs().getPacketScan().getScannedFrameCount()).isEqualTo(5);
    assertThat(rule.getInputs().getPacketScan().getMotionRate()).isEqualTo(50);
  }

  @ParameterizedTest(name = "{0}={1}, fast path={2}")
  @CsvSource({
    "AVERAGE, 249974/10000, false",
    "AVERAGE, 249975/10000, true",
    "AVERAGE, 249976/10000, true",
    "AVERAGE, 250024/10000, true",
    "AVERAGE, 250025/10000, true",
    "AVERAGE, 250026/10000, false",
    "BASE, 249974/10000, false",
    "BASE, 249975/10000, true",
    "BASE, 249976/10000, true",
    "BASE, 250024/10000, true",
    "BASE, 250025/10000, true",
    "BASE, 250026/10000, false"
  })
  @DisplayName("Should require agreement from each declared rate when it crosses 0.01 percent")
  void shouldRequireAgreementFromEachDeclaredRateWhenItCrosses001Percent(
      RateInput input, String rate, boolean takesFastPath) throws Exception {
    var stream = video().baseRate("25/1").averageRate("25/1");
    var changed =
        switch (input) {
          case AVERAGE -> stream.averageRate(rate);
          case BASE -> stream.baseRate(rate);
        };

    var rule = probe(changed.packets(packets(intervals().repeat(40, 10).build())).build());

    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(25, 1));
    assertThat(rule.getBasis())
        .isEqualTo(
            takesFastPath
                ? OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES
                : OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN);
    assertThat(rule.getInputs().hasPacketScan()).isEqualTo(!takesFastPath);
  }

  @ParameterizedTest(name = "{0} frames over 1000 seconds, fast path={1}")
  @CsvSource({
    "23987, false",
    "23988, true",
    "23989, true",
    "24011, true",
    "24012, true",
    "24013, false"
  })
  @DisplayName("Should require statistics agreement when it crosses 0.05 percent of the average")
  void shouldRequireStatisticsAgreementWhenItCrosses005PercentOfTheAverage(
      String frames, boolean takesFastPath) throws Exception {
    var rule =
        probe(
            video()
                .baseRate("24/1")
                .numberOfFrames(frames)
                .duration("00:16:40.000000000")
                .packets(packets(intervals().repeat(40, 10).build()))
                .build());

    assertThat(rule.getBasis())
        .isEqualTo(
            takesFastPath
                ? OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES
                : OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN);
    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(takesFastPath ? 24 : 25, 1));
  }

  @ParameterizedTest(name = "{0}/{1}")
  @CsvSource({
    "10, 1", "12, 1", "25, 2", "15, 1", "24000, 1001", "24, 1", "25, 1",
    "30000, 1001", "30, 1", "48000, 1001", "48, 1", "50, 1", "60000, 1001", "60, 1"
  })
  @DisplayName("Should retain the exact listed rate when both declared rates agree")
  void shouldRetainTheExactListedRateWhenBothDeclaredRatesAgree(int numerator, int denominator)
      throws Exception {
    var declared = numerator + "/" + denominator;
    var rule = probe(video().baseRate(declared).averageRate(declared).build());

    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(numerator, denominator));
    assertThat(rule.getBasis())
        .isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES);
    assertThat(rule.getInputs().hasPacketScan()).isFalse();
  }

  @ParameterizedTest(name = "{0} -> {1}/{2}")
  @CsvSource({
    "9, 10, 1",
    "1/2, 10, 1",
    "6001/100, 60, 1",
    "603/10, 60, 1",
    "239/10, 24000, 1001",
    "62/5, 25, 2",
    "120, 60, 1",
    "120000/1001, 60000, 1001",
    "100, 50, 1",
    "90, 30, 1",
    "72, 24, 1",
    "240, 60, 1",
    "144, 48, 1",
    "80, 60, 1",
    "6031/100, 60, 1"
  })
  @DisplayName("Should snap the average rate when the scan has no usable frames")
  void shouldSnapTheAverageRateWhenTheScanHasNoUsableFrames(
      String average, int numerator, int denominator) throws Exception {
    var rule = probe(video().baseRate("0/0").averageRate(average).build());

    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(numerator, denominator));
    assertThat(rule.getBasis())
        .isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATE_FALLBACK);
  }

  @Test
  @DisplayName("Should scan the chosen video for thirty seconds when cover art precedes it")
  void shouldScanTheChosenVideoForThirtySecondsWhenCoverArtPrecedesIt() throws Exception {
    var rule =
        probe(
            video()
                .streamIndex(7)
                .coverArt(true)
                .packets(packets(intervals().repeat(40, 10).build()))
                .build());

    assertThat(rule.getBasis()).isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN);
    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(25, 1));
    assertThat(rule.getInputs().getPacketScan().getScannedFrameCount()).isEqualTo(11);
  }

  @Test
  @DisplayName("Should measure the motion rate when the scan contains exactly two frames")
  void shouldMeasureTheMotionRateWhenTheScanContainsExactlyTwoFrames() throws Exception {
    var rule = probe(video().packets(packets(intervals().repeat(40, 1).build())).build());

    assertThat(rule.getBasis()).isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN);
    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(25, 1));
    assertThat(rule.getInputs().getPacketScan().getScannedFrameCount()).isEqualTo(2);
    assertThat(rule.getInputs().getPacketScan().getMotionRate()).isEqualTo(25);
  }

  @Test
  @DisplayName("Should use the average rate when it lies just below the usable rate limit")
  void shouldUseTheAverageRateWhenItLiesJustBelowTheUsableRateLimit() throws Exception {
    var rule = probe(video().baseRate("0/0").averageRate("9999/10").build());

    assertThat(rule.getBasis())
        .isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATE_FALLBACK);
    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(60, 1));
  }

  private enum RateInput {
    AVERAGE,
    BASE
  }

  private FrameRateRuleResult probe(Video video) throws Exception {
    Files.writeString(root.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var executor = new FfprobeExecutor(new ObjectMapper(), video::process);
    try (var worker = workerBuilder(root).runtime(runtime).ffprobe(executor).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), requestBuilder().build());
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.hasMedia()).isTrue();
                assertThat(result.getMedia().hasFrameRateRule()).isTrue();
              });
      return runtime.connection().results().getFirst().getMedia().getFrameRateRule();
    }
  }

  private static Video.VideoBuilder video() {
    return Video.builder()
        .baseRate("30/1")
        .averageRate("24/1")
        .fieldOrder("progressive")
        .timeBase("1/1000")
        .packets("");
  }

  private static Fraction fraction(int numerator, int denominator) {
    return Fraction.newBuilder().setNumerator(numerator).setDenominator(denominator).build();
  }

  private static String packets(List<Long> intervals) {
    var output = new StringBuilder("0,0,100,K__,\n");
    long timestamp = 0;
    for (var interval : intervals) {
      timestamp += interval;
      output.append("%d,%d,100,___,\n".formatted(timestamp, timestamp));
    }

    return output.toString();
  }

  private static Intervals intervals() {
    return new Intervals();
  }

  private static final class Intervals {
    private final List<Long> values = new ArrayList<>();

    Intervals repeat(long ticks, int count) {
      for (var index = 0; index < count; index++) {
        values.add(ticks);
      }

      return this;
    }

    List<Long> build() {
      return List.copyOf(values);
    }
  }

  @Builder
  private record Video(
      String baseRate,
      String averageRate,
      String fieldOrder,
      String timeBase,
      String numberOfFrames,
      String duration,
      String packets,
      int streamIndex,
      boolean coverArt) {

    Process process(ProbeCommand command) {
      var arguments = command.arguments();
      if (!arguments.contains("-read_intervals")) {
        return ScriptedProcess.builder().output(json().getBytes(StandardCharsets.UTF_8)).build();
      }

      if (!option(arguments, "-read_intervals").equals("%+30")
          || !option(arguments, "-show_entries").equals("packet=pts,dts,size,flags")
          || !option(arguments, "-of").equals("csv=p=0")) {
        return ScriptedProcess.builder().exitCode(99).build();
      }

      var output =
          option(arguments, "-select_streams").equals(Integer.toString(streamIndex))
              ? packets
              : "0,0,100,K__,\n1000,1000,100,___,\n";
      return ScriptedProcess.builder().output(output.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static String option(List<String> arguments, String flag) {
      var index = arguments.indexOf(flag);
      return index < 0 || index + 1 == arguments.size() ? "" : arguments.get(index + 1);
    }

    String json() {
      var tags = new StringBuilder();
      if (numberOfFrames != null) {
        tags.append("\"NUMBER_OF_FRAMES-eng\":\"").append(numberOfFrames).append('"');
      }

      if (duration != null) {
        tags.append(tags.isEmpty() ? "" : ",")
            .append("\"DURATION-eng\":\"")
            .append(duration)
            .append('"');
      }

      var attachedPicture =
          coverArt
              ? """
                  {"index":0,"codec_type":"video","disposition":{"attached_pic":1}},
                  """
              : "";
      return """
          {"streams":[%s{"index":%d,"codec_type":"video","r_frame_rate":"%s",
            "avg_frame_rate":"%s","field_order":"%s","time_base":"%s","tags":{%s}}]}
          """
          .formatted(
              attachedPicture, streamIndex, baseRate, averageRate, fieldOrder, timeBase, tags);
    }
  }
}
