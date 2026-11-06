package com.streamarr.transcode.engine;

import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;

/**
 * The mapping of one component in rpu_data_mapping: the pivots that split the base layer's range
 * into pieces, and one piece for each, a polynomial or an MMR. The pivots come before every
 * component's pieces in the RPU, so the mapping parses them in two passes.
 *
 * @param pivots the pivot values, in base-layer bit depth; two more than num_pivots_minus2
 */
record RpuReshapingCurve(@NonNull List<Integer> pivots, @NonNull List<Piece> pieces) {

  private static final int MAXIMUM_PIECES = 8;
  private static final long POLYNOMIAL = 0;
  private static final long MMR = 1;
  private static final int MMR_COEFFICIENTS_PER_ORDER = 7;

  RpuReshapingCurve {
    pivots = List.copyOf(pivots);
    pieces = List.copyOf(pieces);
  }

  /** A piece of a curve: how one range of base-layer values maps. */
  sealed interface Piece {
    void write(RpuBitWriter writer, RpuDataHeader header);
  }

  /** A polynomial of order {@code orderMinus1 + 1} with {@code orderMinus1 + 2} coefficients. */
  record Polynomial(
      long orderMinus1, @NonNull List<Long> integerParts, @NonNull List<Long> fractions)
      implements Piece {

    Polynomial {
      integerParts = List.copyOf(integerParts);
      fractions = List.copyOf(fractions);
    }

    /** The piece that maps every value onto itself: 0 + 1 × x. */
    static Polynomial identity() {
      return new Polynomial(0, List.of(0L, 1L), List.of(0L, 0L));
    }

    private static Polynomial parse(RpuBitReader reader, RpuDataHeader header) {
      var orderMinus1 = reader.unsignedExpGolomb();
      if (orderMinus1 > 1) {
        throw new DolbyVisionRpuException("a polynomial piece of order " + (orderMinus1 + 1));
      }

      if (orderMinus1 == 0 && reader.flag()) {
        throw new DolbyVisionRpuException("a polynomial piece with linear interpolation");
      }

      var integerParts = new ArrayList<Long>();
      var fractions = new ArrayList<Long>();
      for (var coefficient = 0; coefficient < orderMinus1 + 2; coefficient++) {
        integerParts.add(reader.signedExpGolomb());
        fractions.add(reader.bits(header.coefficientBits()));
      }

      return new Polynomial(orderMinus1, integerParts, fractions);
    }

    @Override
    public void write(RpuBitWriter writer, RpuDataHeader header) {
      writer.unsignedExpGolomb(POLYNOMIAL).unsignedExpGolomb(orderMinus1);
      if (orderMinus1 == 0) {
        writer.flag(false);
      }

      for (var coefficient = 0; coefficient < fractions.size(); coefficient++) {
        writer.signedExpGolomb(integerParts.get(coefficient));
        writer.bits(fractions.get(coefficient), header.coefficientBits());
      }
    }
  }

  /**
   * A multivariate multiple regression of order {@code orderMinus1 + 1}: a constant, then seven
   * coefficients for each order.
   */
  record Mmr(
      int orderMinus1,
      @NonNull List<Long> constantIntegerPart,
      long constantFraction,
      @NonNull List<List<Long>> integerParts,
      @NonNull List<List<Long>> fractions)
      implements Piece {

    Mmr {
      constantIntegerPart = List.copyOf(constantIntegerPart);
      integerParts = integerParts.stream().map(List::copyOf).toList();
      fractions = fractions.stream().map(List::copyOf).toList();
    }

    private static Mmr parse(RpuBitReader reader, RpuDataHeader header) {
      var orderMinus1 = (int) reader.bits(2);
      if (orderMinus1 > 2) {
        throw new DolbyVisionRpuException("an MMR piece of order " + (orderMinus1 + 1));
      }

      var constantIntegerPart = List.of(reader.signedExpGolomb());
      var constantFraction = reader.bits(header.coefficientBits());
      var integerParts = new ArrayList<List<Long>>();
      var fractions = new ArrayList<List<Long>>();
      for (var order = 0; order <= orderMinus1; order++) {
        var orderIntegerParts = new ArrayList<Long>();
        var orderFractions = new ArrayList<Long>();
        for (var coefficient = 0; coefficient < MMR_COEFFICIENTS_PER_ORDER; coefficient++) {
          orderIntegerParts.add(reader.signedExpGolomb());
          orderFractions.add(reader.bits(header.coefficientBits()));
        }

        integerParts.add(orderIntegerParts);
        fractions.add(orderFractions);
      }

      return new Mmr(orderMinus1, constantIntegerPart, constantFraction, integerParts, fractions);
    }

    @Override
    public void write(RpuBitWriter writer, RpuDataHeader header) {
      writer.unsignedExpGolomb(MMR).bits(orderMinus1, 2);
      constantIntegerPart.forEach(writer::signedExpGolomb);
      writer.bits(constantFraction, header.coefficientBits());
      for (var order = 0; order < fractions.size(); order++) {
        writeOrder(writer, header, order);
      }
    }

    private void writeOrder(RpuBitWriter writer, RpuDataHeader header, int order) {
      for (var coefficient = 0; coefficient < MMR_COEFFICIENTS_PER_ORDER; coefficient++) {
        writer.signedExpGolomb(integerParts.get(order).get(coefficient));
        writer.bits(fractions.get(order).get(coefficient), header.coefficientBits());
      }
    }
  }

  /** The curve that leaves a 10-bit base layer as it is: one identity piece over its range. */
  static RpuReshapingCurve identity() {
    return new RpuReshapingCurve(List.of(0, 1023), List.of(Polynomial.identity()));
  }

  /**
   * The pivots of one component, which precede every component's pieces.
   *
   * @throws DolbyVisionRpuException when they split the range into more than eight pieces
   */
  static List<Integer> parsePivots(RpuBitReader reader, RpuDataHeader header) {
    var pivotsMinus2 = reader.unsignedExpGolomb();
    if (pivotsMinus2 >= MAXIMUM_PIECES) {
      throw new DolbyVisionRpuException("a curve of " + (pivotsMinus2 + 1) + " pieces");
    }

    var pivots = new ArrayList<Integer>();
    for (var pivot = 0; pivot < pivotsMinus2 + 2; pivot++) {
      pivots.add((int) reader.bits(header.baseLayerBitDepth()));
    }

    return pivots;
  }

  /** The pieces of one component, one for each range between its pivots. */
  static RpuReshapingCurve parsePieces(
      RpuBitReader reader, RpuDataHeader header, List<Integer> pivots) {
    var pieces = new ArrayList<Piece>();
    for (var piece = 0; piece < pivots.size() - 1; piece++) {
      pieces.add(parsePiece(reader, header));
    }

    return new RpuReshapingCurve(pivots, pieces);
  }

  // The mapping_idc is compared as the ue(v) the reader returned, never narrowed first: a 32-bit
  // view of 2^32 would read as the polynomial's 0.
  private static Piece parsePiece(RpuBitReader reader, RpuDataHeader header) {
    var mappingIdc = reader.unsignedExpGolomb();
    if (mappingIdc == POLYNOMIAL) {
      return Polynomial.parse(reader, header);
    }

    if (mappingIdc == MMR) {
      return Mmr.parse(reader, header);
    }

    throw new DolbyVisionRpuException("mapping_idc " + mappingIdc);
  }

  void writePivots(RpuBitWriter writer, RpuDataHeader header) {
    writer.unsignedExpGolomb(pivots.size() - 2L);
    pivots.forEach(pivot -> writer.bits(pivot, header.baseLayerBitDepth()));
  }

  void writePieces(RpuBitWriter writer, RpuDataHeader header) {
    pieces.forEach(piece -> piece.write(writer, header));
  }
}
