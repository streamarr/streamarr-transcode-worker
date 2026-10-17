package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.FrameRatePacketScan;
import build.buf.gen.streamarr.transcode.v1.FrameRateRuleResult;
import build.buf.gen.streamarr.transcode.v1.OutputFrameRateBasis;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * ADR 0037's frame-rate rule for the chosen video stream. The declared rates decide when they agree
 * on a listed rate. Otherwise a scan of the first 30 s of packets measures the rate at which the
 * motion runs, and the rule snaps that rate to the list.
 */
@RequiredArgsConstructor
final class FrameRateRule {

  // Matroska's statistics agree with the average rate within 0.05 %.
  private static final ExactFraction STATISTICS_AGREEMENT = ExactFraction.of(5, 10_000);

  @NonNull private final PacketTimings packetTimings;

  @FunctionalInterface
  interface PacketTimings {
    /** ffprobe's packet lines, pts,dts,size,flags, for the first 30 s of the stream. */
    List<String> firstSeconds(int streamIndex) throws ExecutionException, InterruptedException;
  }

  FrameRateRuleResult choose(ChosenVideoStream chosen)
      throws ExecutionException, InterruptedException {
    var timing = FrameTiming.of(chosen.description());
    var result = FrameRateRuleResult.newBuilder();
    var inputs = timing.inputs();
    var declared = declaredRate(timing);
    var statisticsAgree = statisticsAgree(timing);
    if (declared.isPresent() && statisticsAgree) {
      return result
          .setOutputFrameRate(declared.get().toProto().orElseThrow())
          .setBasis(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES)
          .setInputs(inputs)
          .build();
    }

    if (timing.timeBase().isEmpty()) {
      return fallBack(timing, result.setInputs(inputs));
    }

    var scan =
        PacketCadence.of(
            packetTimings.firstSeconds(chosen.streamIndex()), timing.timeBase().get(), timing);
    var scanned =
        FrameRatePacketScan.newBuilder()
            .setScannedFrameCount(scan.frameCount())
            .setScannedDuration(FrameTiming.duration(seconds(scan.scannedSeconds())));
    var motionRate = scan.motionRate();
    motionRate.map(ExactFraction::doubleValue).ifPresent(scanned::setMotionRate);
    inputs.setPacketScan(scanned);
    result.setInputs(inputs);
    if (motionRate.isEmpty()) {
      return fallBack(timing, result);
    }

    var keepsDeclared =
        declared.isPresent()
            && timing
                .matroskaRate()
                .filter(rate -> rate.isGreaterThan(motionRate.get()))
                .isPresent();
    if (keepsDeclared) {
      return result
          .setOutputFrameRate(declared.get().toProto().orElseThrow())
          .setBasis(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_MATROSKA_STATISTICS)
          .build();
    }

    return result
        .setOutputFrameRate(ListedFrameRates.snap(motionRate.get()).toProto().orElseThrow())
        .setBasis(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN)
        .build();
  }

  /**
   * The listed rate that the average rate and the base rate agree on within 0.01 %. On an
   * interlaced stream, the base rate can instead be twice the listed rate.
   */
  private static Optional<ExactFraction> declaredRate(FrameTiming timing) {
    var listed = timing.usableAverageRate().flatMap(ListedFrameRates::agreeingWith);
    var base = timing.usableBaseRate();
    if (listed.isEmpty() || base.isEmpty()) {
      return Optional.empty();
    }

    var rate = listed.get();
    var doubled = rate.times(ExactFraction.of(2));
    if (ListedFrameRates.agree(base.get(), rate)
        || (timing.isInterlaced() && ListedFrameRates.agree(base.get(), doubled))) {
      return listed;
    }

    return Optional.empty();
  }

  private static boolean statisticsAgree(FrameTiming timing) {
    var statistics = timing.matroskaRate();
    var average = timing.usableAverageRate();
    if (statistics.isEmpty() || average.isEmpty()) {
      return true;
    }

    var deviation = statistics.get().dividedBy(average.get()).minus(ExactFraction.of(1)).abs();
    return !deviation.isGreaterThan(STATISTICS_AGREEMENT);
  }

  /** The scan found fewer than 2 frames: snap the average rate, else the base rate, else refuse. */
  private static FrameRateRuleResult fallBack(
      FrameTiming timing, FrameRateRuleResult.Builder result) {
    var declared = timing.usableAverageRate().or(timing::usableBaseRate);
    if (declared.isEmpty()) {
      return result.setBasis(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_REFUSED).build();
    }

    return result
        .setOutputFrameRate(ListedFrameRates.snap(declared.get()).toProto().orElseThrow())
        .setBasis(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATE_FALLBACK)
        .build();
  }

  private static BigDecimal seconds(ExactFraction seconds) {
    return new BigDecimal(seconds.numerator())
        .divide(new BigDecimal(seconds.denominator()), MathContext.DECIMAL128);
  }
}
