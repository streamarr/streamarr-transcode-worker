package com.streamarr.transcode.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The phase filters of the downmix limiter's detector (ADR 0050), read from a checked-in resource
 * so that the worker runs the vectors that the research measured, bit for bit. They are never
 * computed here: a different Bessel function or summation order would change their last bits.
 */
final class DownmixLimiterPhases {

  static final String RESOURCE = "downmix-limiter-phases.txt";

  /** The order of the vectors in the resource and in the first stage's detector. */
  static final List<String> NAMES =
      List.of("k1", "k2", "k3", "k4", "k5", "k6", "k7", "bs0", "bs1", "bs2", "bs3");

  /** The integer delay of every first-stage vector. */
  static final int FIRST_STAGE_DELAY = 64;

  /** The integer delay of the Annex 2 phases as published, which the second stage runs. */
  static final int SECOND_STAGE_DELAY = 6;

  private static final int ANNEX_TWO_TAPS = 12;
  private static final String VECTOR = "vector ";

  private static final Map<String, double[]> VECTORS = read();

  private DownmixLimiterPhases() {}

  /** Each vector by name, in resource order. */
  static Map<String, double[]> vectors() {
    var copy = new LinkedHashMap<String, double[]>();
    VECTORS.forEach((name, taps) -> copy.put(name, taps.clone()));
    return copy;
  }

  /** The first stage's detector: seven Kaiser phases, then the four Annex 2 phases at delay 64. */
  static List<double[]> firstStage() {
    return NAMES.stream().map(name -> VECTORS.get(name).clone()).toList();
  }

  /** The second stage's detector: the four Annex 2 phases, the last twelve taps of each array. */
  static List<double[]> secondStage() {
    return NAMES.stream()
        .filter(name -> name.startsWith("bs"))
        .map(VECTORS::get)
        .map(taps -> Arrays.copyOfRange(taps, taps.length - ANNEX_TWO_TAPS, taps.length))
        .toList();
  }

  private static Map<String, double[]> read() {
    var stream = DownmixLimiterPhases.class.getResourceAsStream(RESOURCE);
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(stream, RESOURCE), StandardCharsets.US_ASCII))) {
      return parse(
          reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList());
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + RESOURCE, e);
    }
  }

  private static Map<String, double[]> parse(List<String> lines) {
    var vectors = new LinkedHashMap<String, double[]>();
    var name = "";
    var taps = new ArrayList<Double>();
    for (var line : lines) {
      if (line.startsWith(VECTOR)) {
        put(vectors, name, taps);
        name = line.substring(VECTOR.length()).split(" ")[0];
        taps = new ArrayList<>();
        continue;
      }

      taps.add(Double.parseDouble(line));
    }

    put(vectors, name, taps);
    return vectors;
  }

  private static void put(Map<String, double[]> vectors, String name, List<Double> taps) {
    if (!name.isEmpty()) {
      vectors.put(name, taps.stream().mapToDouble(Double::doubleValue).toArray());
    }
  }
}
