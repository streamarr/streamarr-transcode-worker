package com.streamarr.transcode.probe;

import java.util.Arrays;
import java.util.Optional;
import lombok.NonNull;

final class DisplayMatrixRotation {

  private DisplayMatrixRotation() {}

  static Optional<Integer> read(@NonNull String displayMatrix) {
    var rows = displayMatrix.strip().lines().map(DisplayMatrixRotation::row).toList();
    if (rows.size() != 3 || rows.stream().anyMatch(Optional::isEmpty)) {
      return Optional.empty();
    }

    var first = rows.getFirst().orElseThrow();
    var second = rows.get(1).orElseThrow();
    var horizontalScale = Math.hypot(first[0], second[0]);
    var verticalScale = Math.hypot(first[1], second[1]);
    if (horizontalScale == 0 || verticalScale == 0) {
      return Optional.empty();
    }

    var degrees = -Math.atan2(first[1] / verticalScale, first[0] / horizontalScale) * 180 / Math.PI;
    return Optional.of((int) Math.copySign(Math.floor(Math.abs(degrees) + 0.5), degrees));
  }

  private static Optional<int[]> row(String line) {
    var separator = line.indexOf(':');
    if (separator < 0) {
      return Optional.empty();
    }

    var values = line.substring(separator + 1).strip().split("\\s+");
    if (values.length != 3) {
      return Optional.empty();
    }

    try {
      return Optional.of(Arrays.stream(values).mapToInt(Integer::parseInt).toArray());
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }
}
