package com.streamarr.transcode.engine;

/**
 * The example true-peak filter of ITU-R BS.1770-5 Annex 2, written from Table 2's published taps
 * and independent of the worker's resource. It reads every phase of the full convolution, the ring
 * after the last sample included, so that no instant near either end of a file goes unread.
 */
final class AnnexTwoMeter {

  /** Table 2: the four phases of the order-48 interpolating filter, twelve taps each. */
  static final double[][] PHASES = {
    {
      0.0017089843750, 0.0109863281250, -0.0196533203125, 0.0332031250000, -0.0594482421875,
      0.1373291015625, 0.9721679687500, -0.1022949218750, 0.0476074218750, -0.0266113281250,
      0.0148925781250, -0.0083007812500
    },
    {
      -0.0291748046875, 0.0292968750000, -0.0517578125000, 0.0891113281250, -0.1665039062500,
      0.4650878906250, 0.7797851562500, -0.2003173828125, 0.1015625000000, -0.0582275390625,
      0.0330810546875, -0.0189208984375
    },
    {
      -0.0189208984375, 0.0330810546875, -0.0582275390625, 0.1015625000000, -0.2003173828125,
      0.7797851562500, 0.4650878906250, -0.1665039062500, 0.0891113281250, -0.0517578125000,
      0.0292968750000, -0.0291748046875
    },
    {
      -0.0083007812500, 0.0148925781250, -0.0266113281250, 0.0476074218750, -0.1022949218750,
      0.9721679687500, 0.1373291015625, -0.0594482421875, 0.0332031250000, -0.0196533203125,
      0.0109863281250, 0.0017089843750
    }
  };

  private AnnexTwoMeter() {}

  /** The largest magnitude that any phase of the filter reads from the samples, in dBTP. */
  static double truePeakDb(double[] samples) {
    var peak = 0.0;
    for (var phase : PHASES) {
      for (var out = 0; out < samples.length + phase.length - 1; out++) {
        var sum = 0.0;
        var last = Math.min(phase.length - 1, out);
        for (var tap = Math.max(0, out - samples.length + 1); tap <= last; tap++) {
          sum += phase[tap] * samples[out - tap];
        }

        peak = Math.max(peak, Math.abs(sum));
      }
    }

    return decibels(peak);
  }

  static double decibels(double linear) {
    return 20 * Math.log10(linear);
  }
}
