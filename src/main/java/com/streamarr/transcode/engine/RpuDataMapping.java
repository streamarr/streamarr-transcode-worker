package com.streamarr.transcode.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The rpu_data_mapping of a Dolby Vision RPU: its id and colour format, the three components'
 * reshaping curves, the picture's partitions, and the NLQ of a residual when the header carries
 * one. The RPU lays the fields out as every component's pivots, the NLQ method and pivots, the
 * partitions, every component's pieces, then the NLQ parameters.
 */
@Builder(toBuilder = true)
record RpuDataMapping(
    long vdrRpuId,
    long colorSpace,
    long chromaFormatIdc,
    @NonNull List<RpuReshapingCurve> curves,
    long xPartitionsMinus1,
    long yPartitionsMinus1,
    @NonNull Optional<RpuDataNlq> nlq) {

  private static final int COMPONENTS = 3;

  RpuDataMapping {
    curves = List.copyOf(curves);
  }

  static RpuDataMapping parse(RpuBitReader reader, RpuDataHeader header) {
    var mapping =
        RpuDataMapping.builder()
            .vdrRpuId(reader.unsignedExpGolomb())
            .colorSpace(reader.unsignedExpGolomb())
            .chromaFormatIdc(reader.unsignedExpGolomb());
    var pivots = new ArrayList<List<Integer>>();
    for (var component = 0; component < COMPONENTS; component++) {
      pivots.add(RpuReshapingCurve.parsePivots(reader, header));
    }

    var predPivotValues = Optional.<List<Integer>>empty();
    if (header.carriesResidual()) {
      predPivotValues = Optional.of(RpuDataNlq.parsePredPivotValues(reader, header));
    }

    mapping.xPartitionsMinus1(reader.unsignedExpGolomb());
    mapping.yPartitionsMinus1(reader.unsignedExpGolomb());
    var curves = new ArrayList<RpuReshapingCurve>();
    for (var component = 0; component < COMPONENTS; component++) {
      curves.add(RpuReshapingCurve.parsePieces(reader, header, pivots.get(component)));
    }

    mapping.curves(curves);
    mapping.nlq(predPivotValues.map(values -> RpuDataNlq.parseComponents(reader, header, values)));
    return mapping.build();
  }

  void write(RpuBitWriter writer, RpuDataHeader header) {
    writer
        .unsignedExpGolomb(vdrRpuId)
        .unsignedExpGolomb(colorSpace)
        .unsignedExpGolomb(chromaFormatIdc);
    curves.forEach(curve -> curve.writePivots(writer, header));
    if (header.carriesResidual()) {
      nlq.orElseThrow().writePredPivotValues(writer, header);
    }

    writer.unsignedExpGolomb(xPartitionsMinus1).unsignedExpGolomb(yPartitionsMinus1);
    curves.forEach(curve -> curve.writePieces(writer, header));
    if (header.carriesResidual()) {
      nlq.orElseThrow().writeComponents(writer, header);
    }
  }

  /**
   * This mapping as a profile 8.1 RPU carries it: without the residual's NLQ and with one
   * partition. A full enhancement layer's curves give way to identity curves, because the residual
   * that they were fitted with is gone; a minimal enhancement layer's curves stay.
   */
  RpuDataMapping withoutResidual() {
    var mapped = toBuilder().nlq(Optional.empty()).xPartitionsMinus1(0).yPartitionsMinus1(0);
    if (nlq.filter(residual -> !residual.isMinimalEnhancementLayer()).isPresent()) {
      mapped.curves(List.of(identity(), identity(), identity()));
    }

    return mapped.build();
  }

  private static RpuReshapingCurve identity() {
    return RpuReshapingCurve.identity();
  }
}
