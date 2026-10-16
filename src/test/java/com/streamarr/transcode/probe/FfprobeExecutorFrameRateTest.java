package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.packets;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeFixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeResultWithEndlessScan;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeResultWithScanExitCode;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.FieldOrder;
import build.buf.gen.streamarr.transcode.v1.Fraction;
import build.buf.gen.streamarr.transcode.v1.FrameRateRuleInputs;
import build.buf.gen.streamarr.transcode.v1.FrameRateRuleResult;
import build.buf.gen.streamarr.transcode.v1.MatroskaStatistics;
import build.buf.gen.streamarr.transcode.v1.OutputFrameRateBasis;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import com.google.protobuf.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Frame Rate Tests")
class FfprobeExecutorFrameRateTest {

  /** 17 intervals of 42 ms and 7 of 41 ms: 1,001 ms for 24 frames, 23.976 fps in 1 ms ticks. */
  private static final int[] FILM_INTERVALS = {
    42, 42, 41, 42, 42, 41, 42, 42, 41, 42, 42, 41, 42, 42, 41, 42, 42, 41, 42, 42, 41, 42, 42, 42
  };

  private static final OutputFrameRateBasis DECLARED_RATES =
      OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES;
  private static final OutputFrameRateBasis PACKET_SCAN =
      OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN;

  @Nested
  @DisplayName("Declared Rates")
  class DeclaredRateTests {

    @Test
    @DisplayName("Should keep the declared rate without a scan when both rates agree on it")
    void shouldKeepTheDeclaredRateWithoutAScanWhenBothRatesAgreeOnIt() {
      // A phone recording at a constant 30 fps in a 1/600 time base.
      var rule = probeFixture("mov-phone-hlg-dovi-p84.mov").getFrameRateRule();

      assertThat(rule)
          .isEqualTo(
              FrameRateRuleResult.newBuilder()
                  .setOutputFrameRate(fraction(30, 1))
                  .setBasis(DECLARED_RATES)
                  .setInputs(
                      FrameRateRuleInputs.newBuilder()
                          .setBaseRate(fraction(30, 1))
                          .setAverageRate(fraction(30, 1))
                          .setTimeBase(fraction(1, 600)))
                  .build());
    }

    @Test
    @DisplayName("Should keep the frame rate when an interlaced stream declares twice that rate")
    void shouldKeepTheFrameRateWhenAnInterlacedStreamDeclaresTwiceThatRate() {
      // An AVCHD camcorder file: top field first, r_frame_rate 60000/1001, average 30000/1001.
      var rule = probeFixture("mpegts-avchd-interlaced.mts").getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(30_000, 1001));
      assertThat(rule.getBasis()).isEqualTo(DECLARED_RATES);
      assertThat(rule.getInputs().getFieldOrder()).isEqualTo(FieldOrder.FIELD_ORDER_TT);
      assertThat(rule.getInputs().hasPacketScan()).isFalse();
    }
  }

  @Nested
  @DisplayName("Field Orders")
  class FieldOrderTests {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "tt, FIELD_ORDER_TT",
      "bb, FIELD_ORDER_BB",
      "tb, FIELD_ORDER_TB",
      "bt, FIELD_ORDER_BT"
    })
    @DisplayName(
        "Should keep the frame rate when any interlaced field order declares twice the rate")
    void shouldKeepTheFrameRateWhenAnyInterlacedFieldOrderDeclaresTwiceTheRate(
        String fieldOrder, FieldOrder reported) {
      var stream =
          VideoStream.builder()
              .baseRate("50/1")
              .averageRate("25/1")
              .fieldOrder(fieldOrder)
              .timeBase("1/90000")
              .build();

      var rule = probeOutput(stream.ffprobeOutput()).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(25, 1));
      assertThat(rule.getBasis()).isEqualTo(DECLARED_RATES);
      assertThat(rule.getInputs().getFieldOrder()).isEqualTo(reported);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"progressive, FIELD_ORDER_PROGRESSIVE", "unknown, FIELD_ORDER_UNSPECIFIED"})
    @DisplayName("Should scan the packets when a progressive stream declares twice the rate")
    void shouldScanThePacketsWhenAProgressiveStreamDeclaresTwiceTheRate(
        String fieldOrder, FieldOrder reported) {
      var stream =
          VideoStream.builder()
              .baseRate("50/1")
              .averageRate("25/1")
              .fieldOrder(fieldOrder)
              .timeBase("1/1000")
              .build();

      var rule =
          probeOutput(stream.ffprobeOutput(), repeating(new int[] {40}, 100)).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(25, 1));
      assertThat(rule.getBasis()).isEqualTo(PACKET_SCAN);
      assertThat(rule.getInputs().getFieldOrder()).isEqualTo(reported);
    }

    @Test
    @DisplayName(
        "Should keep a progressive frame and a keyframe whole when they follow a field pair")
    void shouldKeepAProgressiveFrameAndAKeyframeWholeWhenTheyFollowAFieldPair() {
      // 25 fps in a 1/90000 time base: every other frame arrives as two fields 1,800 ticks apart,
      // and every 50th frame is a keyframe in one packet.
      var stream =
          VideoStream.builder()
              .baseRate("30/1")
              .averageRate("25/1")
              .fieldOrder("tt")
              .timeBase("1/90000")
              .build();
      var lines = new StringBuilder();
      for (var frame = 0; frame < 300; frame++) {
        var time = frame * 3600L;
        var keyframe = frame % 50 == 0;
        lines.append("%d,%d,100,%s,%n".formatted(time, time, keyframe ? "K__" : "___"));
        if (!keyframe && frame % 2 == 0) {
          lines.append("%d,%d,100,___,%n".formatted(time + 1800, time + 1800));
        }
      }

      var rule = probeOutput(stream.ffprobeOutput(), lines.toString()).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(25, 1));
      assertThat(rule.getInputs().getPacketScan().getScannedFrameCount()).isEqualTo(300);
    }
  }

  @Nested
  @DisplayName("Packet Scans")
  class PacketScanTests {

    @Test
    @DisplayName("Should scan the packets when the stream declares only an average rate")
    void shouldScanThePacketsWhenTheStreamDeclaresOnlyAnAverageRate() {
      var stream =
          VideoStream.builder().baseRate("0/0").averageRate("24/1").timeBase("1/1000").build();

      var rule =
          probeOutput(stream.ffprobeOutput(), repeating(FILM_INTERVALS, 100)).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(24_000, 1001));
      assertThat(rule.getBasis()).isEqualTo(PACKET_SCAN);
      assertThat(rule.getInputs().hasBaseRate()).isFalse();
    }

    @ParameterizedTest(name = "{0}: {1}/{2}")
    @CsvSource({
      "mov-phone-60.mov, 60, 1",
      "mov-phone-one-gap.mov, 30, 1",
      "mov-phone-rotated.mov, 30, 1",
      "mov-phone-120-declared.mov, 30, 1",
    })
    @DisplayName("Should take the rate at which the motion runs when a phone declares another rate")
    void shouldTakeTheRateAtWhichTheMotionRunsWhenAPhoneDeclaresAnotherRate(
        String fixture, int numerator, int denominator) {
      // Measured on the same files: a 60 fps recording whose average is 59.956, a 30 fps
      // recording with one 133 ms gap, a low-light recording that drops from 30 to 24 fps, and a
      // 30 fps recording that declares r_frame_rate 120.
      var rule = probeFixture(fixture).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(numerator, denominator));
      assertThat(rule.getBasis()).isEqualTo(PACKET_SCAN);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
      "mov-phone-slow-motion.mov, 60",
      "mkv-120fps.mkv, 60",
      "mp4-100fps.mp4, 50",
      "mp4-90fps.mp4, 30",
      "mp4-72fps.mp4, 24",
    })
    @DisplayName("Should divide a rate above 60 evenly when the motion runs that fast")
    void shouldDivideARateAbove60EvenlyWhenTheMotionRunsThatFast(String fixture, int rate) {
      // The slow-motion phone recording runs at up to 240 fps.
      var rule = probeFixture(fixture).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(rate, 1));
      assertThat(rule.getBasis()).isEqualTo(PACKET_SCAN);
    }

    @Test
    @DisplayName("Should report what the scan measured when the rule scans the packets")
    void shouldReportWhatTheScanMeasuredWhenTheRuleScansThePackets() {
      // The recorded scan lists 1,797 packets, each with a picture. The first starts at 0 and the
      // last at 17,973 ticks of 1/600 s: 29.955 s. Most frames last 10 ticks, exactly 1/60 s.
      var scan = probeFixture("mov-phone-60.mov").getFrameRateRule().getInputs().getPacketScan();

      assertThat(scan.getMotionRate()).isEqualTo(60.0);
      assertThat(scan.getScannedFrameCount()).isEqualTo(1797);
      assertThat(scan.getScannedDuration())
          .isEqualTo(Duration.newBuilder().setSeconds(29).setNanos(955_000_000).build());
    }

    @Test
    @DisplayName(
        "Should merge each field pair into one frame when an interlaced stream needs a scan")
    void shouldMergeEachFieldPairIntoOneFrameWhenAnInterlacedStreamNeedsAScan() {
      // The AVCHD camcorder's packets, one field each, under a base rate that agrees with nothing.
      var stream =
          VideoStream.builder()
              .baseRate("120000/1001")
              .averageRate("30000/1001")
              .fieldOrder("tt")
              .timeBase("1/90000")
              .build();

      var rule =
          probeOutput(stream.ffprobeOutput(), packets("mpegts-avchd-interlaced.mts"))
              .getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(30_000, 1001));
      assertThat(rule.getBasis()).isEqualTo(PACKET_SCAN);
    }

    @Test
    @DisplayName("Should count a rate from 60 to 60.3 as 60 when 1 ms timestamps round the cadence")
    void shouldCountARateFrom60To603As60When1MsTimestampsRoundTheCadence() {
      // Intervals of 16, 17, 16, 17 and 17 ms average 16.6 ms: 60.24 fps.
      var stream =
          VideoStream.builder()
              .baseRate("60/1")
              .averageRate("1506/25")
              .fieldOrder("progressive")
              .timeBase("1/1000")
              .build();

      var rule =
          probeOutput(stream.ffprobeOutput(), repeating(new int[] {16, 17, 16, 17, 17}, 600));

      assertThat(rule.getFrameRateRule().getOutputFrameRate()).isEqualTo(fraction(60, 1));
      assertThat(rule.getFrameRateRule().getBasis()).isEqualTo(PACKET_SCAN);
    }
  }

  @Nested
  @DisplayName("Matroska Statistics")
  class MatroskaStatisticsTests {

    @Test
    @DisplayName("Should keep the declared rate when the statistics show faster content later")
    void shouldKeepTheDeclaredRateWhenTheStatisticsShowFasterContentLater() {
      // The declared rates agree on 29.97. The first 30 s run at 23.976, but 2,400 frames over
      // 98.8 s average 24.29 fps over the whole track, more than 1 % faster.
      var stream =
          VideoStream.builder()
              .baseRate("30000/1001")
              .averageRate("30000/1001")
              .fieldOrder("progressive")
              .timeBase("1/1000")
              .numberOfFrames("2400")
              .duration("00:01:38.800000000")
              .build();

      var rule =
          probeOutput(stream.ffprobeOutput(), repeating(FILM_INTERVALS, 700)).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(30_000, 1001));
      assertThat(rule.getBasis())
          .isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_MATROSKA_STATISTICS);
      assertThat(rule.getInputs().getMatroskaStatistics())
          .isEqualTo(
              MatroskaStatistics.newBuilder()
                  .setNumberOfFrames(2400)
                  .setDuration(Duration.newBuilder().setSeconds(98).setNanos(800_000_000))
                  .build());
    }

    @Test
    @DisplayName(
        "Should take the scanned rate when the statistics disagree but show no faster part")
    void shouldTakeTheScannedRateWhenTheStatisticsDisagreeButShowNoFasterPart() {
      // The declared rates agree on 24, but 2,397 frames over 100 s average 23.97 fps.
      var stream =
          VideoStream.builder()
              .baseRate("24/1")
              .averageRate("24/1")
              .fieldOrder("progressive")
              .timeBase("1/1000")
              .numberOfFrames("2397")
              .duration("00:01:40.000000000")
              .build();

      var rule =
          probeOutput(stream.ffprobeOutput(), repeating(FILM_INTERVALS, 700)).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(24_000, 1001));
      assertThat(rule.getBasis()).isEqualTo(PACKET_SCAN);
    }

    @Test
    @DisplayName(
        "Should keep the declared rate without a scan when the statistics agree within 0.05 %")
    void shouldKeepTheDeclaredRateWithoutAScanWhenTheStatisticsAgreeWithin005Percent() {
      // 2,401 frames over 100 s average 24.01 fps: 0.04 % above 24.
      var stream =
          VideoStream.builder()
              .baseRate("24/1")
              .averageRate("24/1")
              .fieldOrder("progressive")
              .timeBase("1/1000")
              .numberOfFrames("2401")
              .duration("00:01:40.000000000")
              .build();

      var rule = probeOutput(stream.ffprobeOutput()).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(24, 1));
      assertThat(rule.getBasis()).isEqualTo(DECLARED_RATES);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"-5", "many"})
    @DisplayName("Should report only the duration when NUMBER_OF_FRAMES holds no count")
    void shouldReportOnlyTheDurationWhenNumberOfFramesHoldsNoCount(String numberOfFrames) {
      var stream =
          VideoStream.builder()
              .baseRate("24/1")
              .averageRate("24/1")
              .timeBase("1/1000")
              .numberOfFrames(numberOfFrames)
              .duration("00:00:10.000000000")
              .build();

      var rule = probeOutput(stream.ffprobeOutput()).getFrameRateRule();

      assertThat(rule.getBasis()).isEqualTo(DECLARED_RATES);
      assertThat(rule.getInputs().getMatroskaStatistics())
          .isEqualTo(
              MatroskaStatistics.newBuilder()
                  .setDuration(Duration.newBuilder().setSeconds(10))
                  .build());
    }

    @Test
    @DisplayName("Should ignore the statistics when their DURATION is zero")
    void shouldIgnoreTheStatisticsWhenTheirDurationIsZero() {
      var stream =
          VideoStream.builder()
              .baseRate("24/1")
              .averageRate("24/1")
              .timeBase("1/1000")
              .numberOfFrames("2400")
              .duration("00:00:00.000000000")
              .build();

      var rule = probeOutput(stream.ffprobeOutput()).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(24, 1));
      assertThat(rule.getBasis()).isEqualTo(DECLARED_RATES);
    }
  }

  @Nested
  @DisplayName("Fallbacks")
  class FallbackTests {

    @Test
    @DisplayName("Should snap the average rate when the scan finds no frames")
    void shouldSnapTheAverageRateWhenTheScanFindsNoFrames() {
      // The base rate of 90,000 is unusable, and the average of 24.02 lies nearest to 24.
      var stream =
          VideoStream.builder()
              .baseRate("90000/1")
              .averageRate("1201/50")
              .timeBase("1/90000")
              .build();

      var rule = probeOutput(stream.ffprobeOutput()).getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(24, 1));
      assertThat(rule.getBasis())
          .isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATE_FALLBACK);
      assertThat(rule.getInputs().getPacketScan().getScannedFrameCount()).isZero();
      assertThat(rule.getInputs().getPacketScan().hasMotionRate()).isFalse();
    }

    @Test
    @DisplayName(
        "Should snap the base rate when the scan finds one frame and the average is unusable")
    void shouldSnapTheBaseRateWhenTheScanFindsOneFrameAndTheAverageIsUnusable() {
      // The statistics cannot agree or disagree with an average rate that is unusable.
      var stream =
          VideoStream.builder()
              .baseRate("25/1")
              .averageRate("0/0")
              .timeBase("1/90000")
              .numberOfFrames("250")
              .duration("00:00:10.000000000")
              .build();

      var rule = probeOutput(stream.ffprobeOutput(), "0,0,100,K__,\n").getFrameRateRule();

      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(25, 1));
      assertThat(rule.getBasis())
          .isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATE_FALLBACK);
      assertThat(rule.getInputs().hasAverageRate()).isFalse();
    }

    @Test
    @DisplayName("Should refuse when the scan finds no frames and neither declared rate is usable")
    void shouldRefuseWhenTheScanFindsNoFramesAndNeitherDeclaredRateIsUsable() {
      // A rate of 1000 or more is unusable.
      var stream =
          VideoStream.builder().baseRate("1000/1").averageRate("0/0").timeBase("1/1000").build();

      var result = probeOutput(stream.ffprobeOutput());

      assertThat(result.getFrameRateRule().getBasis())
          .isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_REFUSED);
      assertThat(result.getFrameRateRule().hasOutputFrameRate()).isFalse();
    }

    @Test
    @DisplayName("Should drop discarded, empty and untimed packets when the scan reads the packets")
    void shouldDropDiscardedEmptyAndUntimedPacketsWhenTheScanReadsThePackets() {
      // Only the packets at 0 hold a timed picture, so the scan finds one frame and falls back.
      var stream =
          VideoStream.builder().baseRate("25/1").averageRate("50/1").timeBase("1/1000").build();

      var rule =
          probeOutput(
                  stream.ffprobeOutput(),
                  """
                  0,0,100,K__,
                  40,40,100,_D_,
                  80,80,0,___,
                  N/A,N/A,100,___,
                  later,sooner,100,___,
                  0,0,100,K,
                  bad line
                  """)
              .getFrameRateRule();

      assertThat(rule.getInputs().getPacketScan().getScannedFrameCount()).isEqualTo(1);
      assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(50, 1));
    }
  }

  @Test
  @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName("Should fall back to the declared rate when every scanned packet sits at 0")
  void shouldFallBackToTheDeclaredRateWhenEveryScannedPacketSitsAt0() {
    // ffprobe's 30 s read interval ends only when the timestamps pass 30 s, so a stream whose
    // packets all sit at 0 prints every packet in the file. This scan never ends on its own.
    var stream =
        VideoStream.builder().baseRate("25/1").averageRate("50/1").timeBase("1/1000").build();

    var result = probeResultWithEndlessScan(stream.ffprobeOutput(), _ -> "0,0,100,___,");

    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_UNSPECIFIED);
    var rule = result.getMedia().getFrameRateRule();
    assertThat(rule.getInputs().getPacketScan().getScannedFrameCount()).isEqualTo(1);
    assertThat(rule.getOutputFrameRate()).isEqualTo(fraction(50, 1));
    assertThat(rule.getBasis())
        .isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATE_FALLBACK);
  }

  @Test
  @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName("Should stop the scan after 120,000 packets when ffprobe keeps printing packets")
  void shouldStopTheScanAfter120000PacketsWhenFfprobeKeepsPrintingPackets() {
    // One packet every 1 ms. The 120,000th packet starts at 119.999 s.
    var stream =
        VideoStream.builder().baseRate("25/1").averageRate("50/1").timeBase("1/1000").build();

    var result =
        probeResultWithEndlessScan(
            stream.ffprobeOutput(), packet -> "%d,%d,100,___,".formatted(packet, packet));

    var scan = result.getMedia().getFrameRateRule().getInputs().getPacketScan();
    assertThat(scan.getScannedFrameCount()).isEqualTo(120_000);
    assertThat(scan.getScannedDuration())
        .isEqualTo(Duration.newBuilder().setSeconds(119).setNanos(999_000_000).build());
  }

  @Test
  @DisplayName("Should fail the probe when ffprobe's frame-rate scan fails")
  void shouldFailTheProbeWhenFfprobesFrameRateScanFails() {
    var stream =
        VideoStream.builder().baseRate("25/1").averageRate("50/1").timeBase("1/1000").build();

    var result = probeResultWithScanExitCode(stream.ffprobeOutput(), 1);

    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  /** Packet lines with the given intervals in ticks, repeated until {@code count} frames. */
  private static String repeating(int[] intervals, int count) {
    var time = new long[] {0};
    return IntStream.range(0, count)
        .mapToObj(
            frame -> {
              var line = "%d,%d,100,%s,".formatted(time[0], time[0], frame == 0 ? "K__" : "___");
              time[0] += intervals[frame % intervals.length];
              return line;
            })
        .collect(Collectors.joining("\n"));
  }

  private static Fraction fraction(int numerator, int denominator) {
    return Fraction.newBuilder().setNumerator(numerator).setDenominator(denominator).build();
  }

  /** ffprobe's output for one video stream with the given timing fields. */
  @Builder
  private record VideoStream(
      String baseRate,
      String averageRate,
      String fieldOrder,
      String timeBase,
      String numberOfFrames,
      String duration) {

    String ffprobeOutput() {
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

      var fieldOrderField = fieldOrder == null ? "" : ",\"field_order\":\"" + fieldOrder + "\"";
      return """
          {"streams":[{"index":0,"codec_type":"video","r_frame_rate":"%s","avg_frame_rate":"%s",
                       "time_base":"%s"%s,"tags":{%s}}]}
          """
          .formatted(baseRate, averageRate, timeBase, fieldOrderField, tags);
    }
  }
}
