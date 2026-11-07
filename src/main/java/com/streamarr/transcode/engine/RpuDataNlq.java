package com.streamarr.transcode.engine;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * The non-linear quantization of a profile 7 RPU's residual: the method and its two pivots, which
 * the RPU carries ahead of the partitions, and each component's parameters, which follow the
 * curves. The parameters tell a minimal enhancement layer from a full one.
 *
 * @param predPivotValues the two nlq_pred_pivot_value fields, in base-layer bit depth
 */
record RpuDataNlq(@NonNull List<Integer> predPivotValues, @NonNull List<Component> components) {

  private static final int LINEAR_DEADZONE = 0;
  private static final int PIVOTS = 2;
  private static final int COMPONENT_COUNT = 3;

  RpuDataNlq {
    predPivotValues = List.copyOf(predPivotValues);
    components = List.copyOf(components);
  }

  /**
   * The NLQ parameters of one component. The integer parts are 0 in floating point, where the RPU
   * carries none.
   */
  @Builder
  record Component(
      int offset,
      long vdrInMaxIntegerPart,
      long vdrInMax,
      long deadzoneSlopeIntegerPart,
      long deadzoneSlope,
      long deadzoneThresholdIntegerPart,
      long deadzoneThreshold) {

    // libdovi's rule: no offset, a clamp of exactly 1.0 and no dead zone.
    boolean isIdentity() {
      return offset == 0
          && vdrInMaxIntegerPart == 1
          && vdrInMax == 0
          && deadzoneSlopeIntegerPart == 0
          && deadzoneSlope == 0
          && deadzoneThresholdIntegerPart == 0
          && deadzoneThreshold == 0;
    }

    private static Component parse(RpuBitReader reader, RpuDataHeader header) {
      return Component.builder()
          .offset((int) reader.bits(header.enhancementLayerBitDepth()))
          .vdrInMaxIntegerPart(reader.unsignedExpGolomb())
          .vdrInMax(reader.bits(header.coefficientBits()))
          .deadzoneSlopeIntegerPart(reader.unsignedExpGolomb())
          .deadzoneSlope(reader.bits(header.coefficientBits()))
          .deadzoneThresholdIntegerPart(reader.unsignedExpGolomb())
          .deadzoneThreshold(reader.bits(header.coefficientBits()))
          .build();
    }

    private void write(RpuBitWriter writer, RpuDataHeader header) {
      writer.bits(offset, header.enhancementLayerBitDepth());
      writer.unsignedExpGolomb(vdrInMaxIntegerPart).bits(vdrInMax, header.coefficientBits());
      writer.unsignedExpGolomb(deadzoneSlopeIntegerPart);
      writer.bits(deadzoneSlope, header.coefficientBits());
      writer.unsignedExpGolomb(deadzoneThresholdIntegerPart);
      writer.bits(deadzoneThreshold, header.coefficientBits());
    }
  }

  /** Whether the enhancement layer is minimal: every component's NLQ is the identity. */
  boolean isMinimalEnhancementLayer() {
    return components.stream().allMatch(Component::isIdentity);
  }

  /**
   * The method and pivots that an RPU with a residual carries ahead of its partitions.
   *
   * @throws DolbyVisionRpuException when the method is not the linear dead zone, the only one the
   *     syntax defines
   */
  static List<Integer> parsePredPivotValues(RpuBitReader reader, RpuDataHeader header) {
    var method = reader.bits(3);
    if (method != LINEAR_DEADZONE) {
      throw new DolbyVisionRpuException("nlq_method_idc " + method);
    }

    var pivots = new ArrayList<Integer>();
    for (var pivot = 0; pivot < PIVOTS; pivot++) {
      pivots.add((int) reader.bits(header.baseLayerBitDepth()));
    }

    return pivots;
  }

  /** The parameters of every component, which follow the curves. */
  static RpuDataNlq parseComponents(
      RpuBitReader reader, RpuDataHeader header, List<Integer> predPivotValues) {
    var components = new ArrayList<Component>();
    for (var component = 0; component < COMPONENT_COUNT; component++) {
      components.add(Component.parse(reader, header));
    }

    return new RpuDataNlq(predPivotValues, components);
  }

  void writePredPivotValues(RpuBitWriter writer, RpuDataHeader header) {
    writer.bits(LINEAR_DEADZONE, 3);
    predPivotValues.forEach(pivot -> writer.bits(pivot, header.baseLayerBitDepth()));
  }

  void writeComponents(RpuBitWriter writer, RpuDataHeader header) {
    components.forEach(component -> component.write(writer, header));
  }
}
