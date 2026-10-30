package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import com.netflix.concurrency.limits.limit.AIMDLimit;
import com.streamarr.transcode.engine.MonotonicClock;
import com.streamarr.transcode.engine.ProductionTiming;
import com.streamarr.transcode.probe.ProbeKind;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The probe capacities that the worker grants, and the probes that run against them, as ADR 0032
 * decides. A playback probe can use a free background unit, and no probe holds a particular unit:
 * the background units in use are the background probes plus the playback probes beyond the
 * playback probe capacity, the formula that the server also uses. The server counts a probe until
 * its result arrives, so the worker accepts every probe that the server sends while no capacity
 * drops.
 *
 * <p>Each change to the granted capacities runs the change callback once, outside this object's
 * monitor.
 */
final class ProbeCapacities {

  static final int PLAYBACK_PROBE_CAPACITY = 2;
  static final int STARTING_BACKGROUND_PROBE_CAPACITY = 4;
  static final double SPEED_FLOOR = 2.10;

  private static final int IDLE_KEYFRAME_PACKET_SCAN_CAPACITY = 1;
  private static final Duration MINIMUM_INTERVAL_BETWEEN_BRAKES = ProductionSpeedWindow.WINDOW;
  private static final Duration GROWTH_HOLD_AFTER_BRAKE = Duration.ofSeconds(30);
  private static final Duration IDLE_BEFORE_KEYFRAME_PACKET_SCANS = Duration.ofSeconds(30);

  private final MonotonicClock clock;
  private final Runnable onChange;
  private final BackgroundLimit backgroundLimit;

  // Guarded by this monitor.
  private final Map<UUID, ProductionSpeedWindow> jobAttempts = new HashMap<>();
  private final Set<UUID> slowJobAttempts = new HashSet<>();
  private final Map<ProbeKind, Integer> running = new EnumMap<>(ProbeKind.class);
  private int keyframePacketScanCapacity = IDLE_KEYFRAME_PACKET_SCAN_CAPACITY;
  private OptionalLong lastBrakeNanos = OptionalLong.empty();
  private OptionalLong lastJobAttemptEndNanos = OptionalLong.empty();
  private int brakes;
  private long brakesApplied;

  @Builder
  private ProbeCapacities(
      @NonNull MonotonicClock clock,
      @NonNull BackgroundProbeCapacity backgroundProbeCapacity,
      @NonNull Runnable onChange) {
    this.clock = clock;
    this.onChange = onChange;
    for (var kind : ProbeKind.values()) {
      running.put(kind, 0);
    }

    this.backgroundLimit =
        switch (backgroundProbeCapacity) {
          case BackgroundProbeCapacity.Adaptive(var maximum) -> new AdaptiveLimit(maximum);
          case BackgroundProbeCapacity.Fixed(var capacity) -> new FixedLimit(capacity);
        };
  }

  synchronized GrantedCapacities granted() {
    return new GrantedCapacities(
        backgroundLimit.value(),
        PLAYBACK_PROBE_CAPACITY,
        keyframePacketScanCapacity,
        backgroundLimit.control());
  }

  /** Counts the probe against its capacity; empty when the capacity has no free unit. */
  synchronized Optional<CountedProbe> tryAcquire(@NonNull ProbeKind kind) {
    if (!fits(kind)) {
      return Optional.empty();
    }

    running.merge(kind, 1, Integer::sum);
    return Optional.of(new CountedProbe(kind, brakesApplied));
  }

  /** Frees the unit of a probe that ended, and grows the background limit when it was busy. */
  void release(@NonNull CountedProbe probe) {
    changeCapacities(
        () -> {
          var inUse = backgroundUnitsInUse();
          running.merge(probe.kind(), -1, Integer::sum);
          if (backgroundUnitsInUse() < inUse && isGrowthAllowed()) {
            backgroundLimit.grow(inUse);
          }
        });
  }

  /**
   * Brakes the background limit when the server cancels a probe at the probe timeout and the probe
   * started after the latest brake. The probe keeps its unit until {@link #releaseTimedOut} frees
   * it.
   */
  void probeTimedOut(@NonNull CountedProbe probe) {
    changeCapacities(
        () -> {
          if (isTimeoutBrakeDue(probe)) {
            brake();
          }
        });
  }

  /** Frees the unit of a probe that the server cancelled at the probe timeout, and never grows. */
  void releaseTimedOut(@NonNull CountedProbe probe) {
    changeCapacities(() -> running.merge(probe.kind(), -1, Integer::sum));
  }

  // A job attempt that starts clears background probes from the host while its viewer waits.
  void jobAttemptStarted(@NonNull UUID jobAttemptId) {
    changeCapacities(
        () -> {
          jobAttempts.put(jobAttemptId, new ProductionSpeedWindow(clock));
          keyframePacketScanCapacity = 0;
          brake();
        });
  }

  void jobAttemptEnded(@NonNull UUID jobAttemptId) {
    changeCapacities(
        () -> {
          jobAttempts.remove(jobAttemptId);
          slowJobAttempts.remove(jobAttemptId);
          if (jobAttempts.isEmpty()) {
            lastJobAttemptEndNanos = OptionalLong.of(clock.nanoTime());
          }
        });
  }

  /** Judges the job attempt's production speed, and brakes once per window while it is slow. */
  void mediaSegmentProduced(@NonNull UUID jobAttemptId, @NonNull ProductionTiming timing) {
    changeCapacities(
        () -> {
          var window = jobAttempts.get(jobAttemptId);
          if (window == null) {
            return;
          }

          window.add(timing);
          var speed = window.judgedSpeed();
          if (speed.isEmpty()) {
            return;
          }

          if (speed.getAsDouble() >= SPEED_FLOOR) {
            slowJobAttempts.remove(jobAttemptId);
            return;
          }

          slowJobAttempts.add(jobAttemptId);
          if (hasBeenSinceLastBrake(MINIMUM_INTERVAL_BETWEEN_BRAKES)) {
            brake();
          }
        });
  }

  /** Reopens keyframe packet scans once no job attempt has run for 30 s. */
  void reopenKeyframePacketScansWhenIdle() {
    changeCapacities(
        () -> {
          if (keyframePacketScanCapacity > 0 || !jobAttempts.isEmpty()) {
            return;
          }

          var idleSince = lastJobAttemptEndNanos.orElseThrow();
          if (clock.since(idleSince).compareTo(IDLE_BEFORE_KEYFRAME_PACKET_SCANS) >= 0) {
            keyframePacketScanCapacity = IDLE_KEYFRAME_PACKET_SCAN_CAPACITY;
          }
        });
  }

  /** The brakes since the previous call. */
  synchronized int takeBrakeCount() {
    var taken = brakes;
    brakes = 0;
    return taken;
  }

  // Guarded by this monitor.
  private boolean fits(ProbeKind kind) {
    return switch (kind) {
      case PLAYBACK ->
          running.get(ProbeKind.PLAYBACK) < PLAYBACK_PROBE_CAPACITY
              || backgroundUnitsInUse() < backgroundLimit.value();
      case BACKGROUND -> backgroundUnitsInUse() < backgroundLimit.value();
      case KEYFRAME_PACKET_SCAN ->
          running.get(ProbeKind.KEYFRAME_PACKET_SCAN) < keyframePacketScanCapacity;
    };
  }

  // Guarded by this monitor.
  private int backgroundUnitsInUse() {
    return running.get(ProbeKind.BACKGROUND)
        + Math.max(0, running.get(ProbeKind.PLAYBACK) - PLAYBACK_PROBE_CAPACITY);
  }

  // Guarded by this monitor.
  private boolean isGrowthAllowed() {
    return slowJobAttempts.isEmpty() && hasBeenSinceLastBrake(GROWTH_HOLD_AFTER_BRAKE);
  }

  // Guarded by this monitor. The probes that were running at the latest brake already count in
  // that brake, as TCP halves its window once for each window of data in flight (RFC 5681).
  private boolean isTimeoutBrakeDue(CountedProbe probe) {
    var loadsTheBackgroundUnits =
        switch (probe.kind()) {
          case BACKGROUND, PLAYBACK -> true;
          case KEYFRAME_PACKET_SCAN -> false;
        };
    return loadsTheBackgroundUnits
        && probe.brakesAppliedAtStart() == brakesApplied
        && hasBeenSinceLastBrake(MINIMUM_INTERVAL_BETWEEN_BRAKES);
  }

  // Guarded by this monitor.
  private void brake() {
    if (!backgroundLimit.tryBrake()) {
      return;
    }

    lastBrakeNanos = OptionalLong.of(clock.nanoTime());
    brakes++;
    brakesApplied++;
  }

  // Guarded by this monitor.
  private boolean hasBeenSinceLastBrake(Duration interval) {
    return lastBrakeNanos.isEmpty()
        || clock.since(lastBrakeNanos.getAsLong()).compareTo(interval) >= 0;
  }

  private void changeCapacities(Runnable mutation) {
    GrantedCapacities before;
    GrantedCapacities after;
    synchronized (this) {
      before = granted();
      mutation.run();
      after = granted();
    }

    if (!after.equals(before)) {
      onChange.run();
    }
  }

  /** A probe that counts against its capacity until a release frees its unit. */
  record CountedProbe(ProbeKind kind, long brakesAppliedAtStart) {}

  private sealed interface BackgroundLimit {

    int value();

    BackgroundProbeCapacityControl control();

    void grow(int inUse);

    /** Lowers the limit; false when the limit never changes. */
    boolean tryBrake();
  }

  // AIMDLimit grows by 1 when the units in use reach half of the limit, and halves on a drop.
  private static final class AdaptiveLimit implements BackgroundLimit {

    private final AIMDLimit limit;

    private AdaptiveLimit(int maximum) {
      limit =
          AIMDLimit.newBuilder()
              .initialLimit(Math.min(STARTING_BACKGROUND_PROBE_CAPACITY, maximum))
              .minLimit(1)
              .maxLimit(maximum)
              .backoffRatio(0.5)
              .build();
    }

    @Override
    public int value() {
      return limit.getLimit();
    }

    @Override
    public BackgroundProbeCapacityControl control() {
      return BackgroundProbeCapacityControl.BACKGROUND_PROBE_CAPACITY_CONTROL_ADAPTIVE;
    }

    @Override
    public void grow(int inUse) {
      limit.onSample(0, 0, inUse, false);
    }

    @Override
    public boolean tryBrake() {
      limit.onSample(0, 0, 0, true);
      return true;
    }
  }

  private record FixedLimit(int value) implements BackgroundLimit {

    @Override
    public BackgroundProbeCapacityControl control() {
      return BackgroundProbeCapacityControl.BACKGROUND_PROBE_CAPACITY_CONTROL_FIXED;
    }

    @Override
    public void grow(int inUse) {
      // A worker setting fixes the capacity.
    }

    @Override
    public boolean tryBrake() {
      return false;
    }
  }
}
