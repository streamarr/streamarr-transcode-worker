package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import com.streamarr.transcode.engine.ProductionTiming;
import com.streamarr.transcode.fixtures.SteppedClock;
import com.streamarr.transcode.probe.ProbeKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Probe Capacities Tests")
class ProbeCapacitiesTest {

  private static final Duration SEGMENT = Duration.ofSeconds(3);

  private final SteppedClock clock = new SteppedClock();
  private final AtomicInteger changes = new AtomicInteger();

  @Test
  @DisplayName("Should grant the starting capacities when no job attempt has run")
  void shouldGrantTheStartingCapacitiesWhenNoJobAttemptHasRun() {
    var capacities = adaptive(16);

    assertThat(capacities.granted())
        .isEqualTo(
            new GrantedCapacities(
                4,
                2,
                1,
                BackgroundProbeCapacityControl.BACKGROUND_PROBE_CAPACITY_CONTROL_ADAPTIVE));
  }

  @Test
  @DisplayName(
      "Should start the background probe capacity at the maximum when the maximum is below 4")
  void shouldStartTheBackgroundProbeCapacityAtTheMaximumWhenTheMaximumIsBelow4() {
    assertThat(adaptive(2).granted().backgroundProbeCapacity()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should grow the background probe capacity by 1 when a probe ends with every unit in use")
  void shouldGrowTheBackgroundProbeCapacityBy1WhenAProbeEndsWithEveryUnitInUse() {
    var capacities = adaptive(16);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 4);

    capacities.release(probes.getFirst());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(5);
    assertThat(changes).hasValue(1);
  }

  @Test
  @DisplayName(
      "Should keep the background probe capacity when a probe ends with less than half in use")
  void shouldKeepTheBackgroundProbeCapacityWhenAProbeEndsWithLessThanHalfInUse() {
    var capacities = adaptive(16);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 1);

    capacities.release(probes.getFirst());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(4);
    assertThat(changes).hasValue(0);
  }

  @Test
  @DisplayName("Should never grow the background probe capacity beyond the maximum")
  void shouldNeverGrowTheBackgroundProbeCapacityBeyondTheMaximum() {
    var capacities = adaptive(4);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 4);

    capacities.release(probes.getFirst());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(4);
  }

  @Test
  @DisplayName("Should count playback probes beyond their own capacity as background units in use")
  void shouldCountPlaybackProbesBeyondTheirOwnCapacityAsBackgroundUnitsInUse() {
    var capacities = adaptive(16);
    acquire(capacities, ProbeKind.BACKGROUND, 2);
    var playback = acquire(capacities, ProbeKind.PLAYBACK, 4);

    assertThat(capacities.tryAcquire(ProbeKind.BACKGROUND)).isEmpty();
    capacities.release(playback.getFirst());
    assertThat(capacities.tryAcquire(ProbeKind.BACKGROUND)).isPresent();
  }

  @Test
  @DisplayName("Should start a playback probe when every background and scan unit is in use")
  void shouldStartAPlaybackProbeWhenEveryBackgroundAndScanUnitIsInUse() {
    var capacities = adaptive(16);
    acquire(capacities, ProbeKind.BACKGROUND, 4);
    acquire(capacities, ProbeKind.KEYFRAME_PACKET_SCAN, 1);

    assertThat(capacities.tryAcquire(ProbeKind.PLAYBACK)).isPresent();
    assertThat(capacities.tryAcquire(ProbeKind.PLAYBACK)).isPresent();
    assertThat(capacities.tryAcquire(ProbeKind.PLAYBACK)).isEmpty();
  }

  @Test
  @DisplayName("Should refuse a background probe when only playback probe units are free")
  void shouldRefuseABackgroundProbeWhenOnlyPlaybackProbeUnitsAreFree() {
    var capacities = fixed(1);
    acquire(capacities, ProbeKind.BACKGROUND, 1);

    assertThat(capacities.tryAcquire(ProbeKind.BACKGROUND)).isEmpty();
    assertThat(capacities.tryAcquire(ProbeKind.PLAYBACK)).isPresent();
  }

  @Test
  @DisplayName("Should refuse a keyframe packet scan when the scan capacity is in use")
  void shouldRefuseAKeyframePacketScanWhenTheScanCapacityIsInUse() {
    var capacities = adaptive(16);
    var scans = acquire(capacities, ProbeKind.KEYFRAME_PACKET_SCAN, 1);

    assertThat(capacities.tryAcquire(ProbeKind.KEYFRAME_PACKET_SCAN)).isEmpty();
    capacities.release(scans.getFirst());
    assertThat(capacities.tryAcquire(ProbeKind.KEYFRAME_PACKET_SCAN)).isPresent();
  }

  @Test
  @DisplayName(
      "Should halve the background probe capacity and close the scan capacity when a job attempt starts")
  void shouldHalveTheBackgroundProbeCapacityAndCloseTheScanCapacityWhenAJobAttemptStarts() {
    var capacities = adaptive(16);

    capacities.jobAttemptStarted(UUID.randomUUID());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(2);
    assertThat(capacities.granted().keyframePacketScanCapacity()).isZero();
    assertThat(changes).as("One change carries both capacities").hasValue(1);
    assertThat(capacities.takeBrakeCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should never lower the background probe capacity below 1 when brakes repeat")
  void shouldNeverLowerTheBackgroundProbeCapacityBelow1WhenBrakesRepeat() {
    var capacities = adaptive(16);

    for (var attempt = 0; attempt < 5; attempt++) {
      capacities.jobAttemptStarted(UUID.randomUUID());
    }

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should not grow the background probe capacity for 30 s after a brake")
  void shouldNotGrowTheBackgroundProbeCapacityFor30SAfterABrake() {
    var capacities = adaptive(16);
    var attempt = UUID.randomUUID();
    capacities.jobAttemptStarted(attempt);
    capacities.jobAttemptEnded(attempt);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 2);

    clock.advance(Duration.ofSeconds(29));
    capacities.release(probes.getFirst());
    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(2);

    var next = capacities.tryAcquire(ProbeKind.BACKGROUND).orElseThrow();
    clock.advance(Duration.ofSeconds(1));
    capacities.release(next);
    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should reopen the scan capacity once no job attempt has run for 30 s")
  void shouldReopenTheScanCapacityOnceNoJobAttemptHasRunFor30S() {
    var capacities = adaptive(16);
    var attempt = UUID.randomUUID();
    capacities.jobAttemptStarted(attempt);
    clock.advance(Duration.ofMinutes(5));
    capacities.jobAttemptEnded(attempt);

    clock.advance(Duration.ofSeconds(29));
    capacities.reopenKeyframePacketScansWhenIdle();
    assertThat(capacities.granted().keyframePacketScanCapacity()).isZero();

    clock.advance(Duration.ofSeconds(1));
    capacities.reopenKeyframePacketScansWhenIdle();
    assertThat(capacities.granted().keyframePacketScanCapacity()).isEqualTo(1);
    assertThat(changes).hasValue(2);
  }

  @Test
  @DisplayName("Should keep the scan capacity closed while another job attempt runs")
  void shouldKeepTheScanCapacityClosedWhileAnotherJobAttemptRuns() {
    var capacities = adaptive(16);
    var ended = UUID.randomUUID();
    capacities.jobAttemptStarted(ended);
    capacities.jobAttemptStarted(UUID.randomUUID());
    capacities.jobAttemptEnded(ended);

    clock.advance(Duration.ofMinutes(1));
    capacities.reopenKeyframePacketScansWhenIdle();

    assertThat(capacities.granted().keyframePacketScanCapacity()).isZero();
  }

  @Test
  @DisplayName("Should ignore the media segments of a job attempt when the attempt has ended")
  void shouldIgnoreTheMediaSegmentsOfAJobAttemptWhenTheAttemptHasEnded() {
    var capacities = adaptive(16);
    var attempt = UUID.randomUUID();
    capacities.jobAttemptStarted(attempt);
    clock.advance(Duration.ofSeconds(30));
    capacities.jobAttemptEnded(attempt);
    capacities.takeBrakeCount();
    var before = capacities.granted();

    produce(capacities, attempt, 10, 1.0);

    assertThat(capacities.granted()).isEqualTo(before);
    assertThat(capacities.takeBrakeCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should halve the background probe capacity when a job attempt produces below the floor for one window")
  void shouldHalveTheBackgroundProbeCapacityWhenAJobAttemptProducesBelowTheFloorForOneWindow() {
    var capacities = adaptive(16);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 4);
    capacities.release(probes.getFirst());
    var attempt = UUID.randomUUID();
    capacities.jobAttemptStarted(attempt);
    clock.advance(Duration.ofSeconds(30));
    capacities.takeBrakeCount();
    var before = capacities.granted().backgroundProbeCapacity();

    produce(capacities, attempt, 10, 2.0);

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(before / 2);
    assertThat(capacities.takeBrakeCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should keep the background probe capacity when a job attempt produces above the floor")
  void shouldKeepTheBackgroundProbeCapacityWhenAJobAttemptProducesAboveTheFloor() {
    var capacities = adaptive(16);
    var attempt = UUID.randomUUID();
    capacities.jobAttemptStarted(attempt);
    clock.advance(Duration.ofSeconds(30));
    var before = capacities.granted().backgroundProbeCapacity();

    produce(capacities, attempt, 20, 2.2);

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(before);
    assertThat(capacities.takeBrakeCount())
        .as("only the brake at the attempt's start")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should grow the background probe capacity again when a slow job attempt recovers above the"
          + " floor")
  void shouldGrowTheBackgroundProbeCapacityAgainWhenASlowJobAttemptRecoversAboveTheFloor() {
    var capacities = adaptive(16);
    var attempt = UUID.randomUUID();
    capacities.jobAttemptStarted(attempt);
    produce(capacities, attempt, 6, 1.0);
    produce(capacities, attempt, 20, 3.0);
    clock.advance(Duration.ofSeconds(30));
    var before = capacities.granted().backgroundProbeCapacity();
    var probes = acquire(capacities, ProbeKind.BACKGROUND, before);

    capacities.release(probes.getFirst());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(before + 1);
  }

  @Test
  @DisplayName(
      "Should halve the background probe capacity once per window while a job attempt stays slow")
  void shouldHalveTheBackgroundProbeCapacityOncePerWindowWhileAJobAttemptStaysSlow() {
    var capacities = adaptive(16);
    var attempt = UUID.randomUUID();
    capacities.jobAttemptStarted(attempt);
    clock.advance(Duration.ofSeconds(30));
    capacities.takeBrakeCount();

    produce(capacities, attempt, 10, 1.0);

    assertThat(capacities.takeBrakeCount())
        .as("30 s of slow production holds two 15 s windows")
        .isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should not grow the background probe capacity while a judged job attempt stays slow")
  void shouldNotGrowTheBackgroundProbeCapacityWhileAJudgedJobAttemptStaysSlow() {
    var capacities = adaptive(16);
    var attempt = UUID.randomUUID();
    capacities.jobAttemptStarted(attempt);
    produce(capacities, attempt, 6, 1.0);
    clock.advance(Duration.ofMinutes(1));
    var before = capacities.granted().backgroundProbeCapacity();
    var probes = acquire(capacities, ProbeKind.BACKGROUND, before);

    capacities.release(probes.getFirst());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(before);
  }

  @ParameterizedTest
  @EnumSource(
      value = ProbeKind.class,
      names = {"BACKGROUND", "PLAYBACK"})
  @DisplayName("Should halve the background probe capacity when a probe ends at the probe timeout")
  void shouldHalveTheBackgroundProbeCapacityWhenAProbeEndsAtTheProbeTimeout(ProbeKind kind) {
    var capacities = adaptive(16);
    var probes = acquire(capacities, kind, 1);

    timeOut(capacities, probes.getFirst());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(2);
    assertThat(capacities.takeBrakeCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should halve the background probe capacity when a probe times out before its unit is free")
  void shouldHalveTheBackgroundProbeCapacityWhenAProbeTimesOutBeforeItsUnitIsFree() {
    var capacities = adaptive(16);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 3);

    capacities.probeTimedOut(probes.getFirst());
    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(2);

    capacities.release(probes.get(1));
    assertThat(capacities.granted().backgroundProbeCapacity())
        .as("Growth holds after the brake although most units are in use")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("Should apply no brake when a keyframe packet scan ends at its timeout")
  void shouldApplyNoBrakeWhenAKeyframePacketScanEndsAtItsTimeout() {
    var capacities = adaptive(16);
    var scans = acquire(capacities, ProbeKind.KEYFRAME_PACKET_SCAN, 1);

    timeOut(capacities, scans.getFirst());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(4);
    assertThat(capacities.takeBrakeCount()).isZero();
    assertThat(changes).hasValue(0);
  }

  @Test
  @DisplayName(
      "Should apply a brake for a timed-out probe only when the probe started after the latest"
          + " brake")
  void shouldApplyABrakeForATimedOutProbeOnlyWhenTheProbeStartedAfterTheLatestBrake() {
    var capacities = adaptive(16);
    var startedBeforeBrake = acquire(capacities, ProbeKind.BACKGROUND, 2);
    timeOut(capacities, startedBeforeBrake.getFirst());
    clock.advance(Duration.ofSeconds(15));

    timeOut(capacities, startedBeforeBrake.getLast());
    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(2);

    var startedAfterBrake = acquire(capacities, ProbeKind.BACKGROUND, 1);
    timeOut(capacities, startedAfterBrake.getFirst());
    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(1);
    assertThat(capacities.takeBrakeCount()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should apply no brake for a timed-out probe until 15 s have passed since the previous"
          + " brake")
  void shouldApplyNoBrakeForATimedOutProbeUntil15SHavePassedSinceThePreviousBrake() {
    var capacities = adaptive(16);
    capacities.jobAttemptStarted(UUID.randomUUID());
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 2);

    clock.advance(Duration.ofSeconds(14));
    timeOut(capacities, probes.getFirst());
    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(2);

    clock.advance(Duration.ofSeconds(1));
    timeOut(capacities, probes.getLast());
    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should never grow the background probe capacity when a busy probe ends at the probe"
          + " timeout")
  void shouldNeverGrowTheBackgroundProbeCapacityWhenABusyProbeEndsAtTheProbeTimeout() {
    var capacities = adaptive(16);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 4);
    timeOut(capacities, probes.get(0));
    clock.advance(Duration.ofSeconds(30));

    timeOut(capacities, probes.get(1));
    assertThat(capacities.granted().backgroundProbeCapacity())
        .as("The probe started before the brake, so it neither brakes nor grows")
        .isEqualTo(2);

    capacities.release(probes.get(2));
    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should keep the background probe capacity at 1 when a probe times out at 1")
  void shouldKeepTheBackgroundProbeCapacityAt1WhenAProbeTimesOutAt1() {
    var capacities = adaptive(1);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 1);

    timeOut(capacities, probes.getFirst());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(1);
    assertThat(capacities.tryAcquire(ProbeKind.BACKGROUND)).isPresent();
  }

  @Test
  @DisplayName("Should keep a fixed background probe capacity when a probe ends at the timeout")
  void shouldKeepAFixedBackgroundProbeCapacityWhenAProbeEndsAtTheTimeout() {
    var capacities = fixed(12);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 1);

    timeOut(capacities, probes.getFirst());

    assertThat(capacities.granted().backgroundProbeCapacity()).isEqualTo(12);
    assertThat(capacities.takeBrakeCount()).isZero();
    assertThat(changes).hasValue(0);
  }

  @Test
  @DisplayName("Should keep a fixed background probe capacity through brakes and busy probes")
  void shouldKeepAFixedBackgroundProbeCapacityThroughBrakesAndBusyProbes() {
    var capacities = fixed(12);
    var probes = acquire(capacities, ProbeKind.BACKGROUND, 12);
    capacities.release(probes.getFirst());

    capacities.jobAttemptStarted(UUID.randomUUID());

    assertThat(capacities.granted())
        .isEqualTo(
            new GrantedCapacities(
                12, 2, 0, BackgroundProbeCapacityControl.BACKGROUND_PROBE_CAPACITY_CONTROL_FIXED));
    assertThat(changes).as("Only the scan capacity changed").hasValue(1);
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
  @DisplayName(
      "Should accept every probe that a server sends under the shared formula when probe ends lag behind")
  void shouldAcceptEveryProbeThatAServerSendsUnderTheSharedFormulaWhenProbeEndsLagBehind(
      long seed) {
    var capacities = fixed(1);
    var random = new Random(seed);
    var server = new ServerCounts(2, 1);
    var running = new ArrayList<ProbeCapacities.CountedProbe>();
    var unreported = new ArrayList<ProbeKind>();

    for (var step = 0; step < 2_000; step++) {
      switch (random.nextInt(3)) {
        case 0 -> {
          var kind = random.nextBoolean() ? ProbeKind.PLAYBACK : ProbeKind.BACKGROUND;
          if (server.fits(kind)) {
            server.sent(kind);
            var counted = capacities.tryAcquire(kind);
            assertThat(counted).as("step %d sends %s", step, kind).isPresent();
            running.add(counted.get());
          }
        }
        case 1 -> {
          if (!running.isEmpty()) {
            var probe = running.remove(random.nextInt(running.size()));
            capacities.release(probe);
            unreported.add(probe.kind());
          }
        }
        default -> {
          if (!unreported.isEmpty()) {
            server.resultArrived(unreported.remove(random.nextInt(unreported.size())));
          }
        }
      }
    }
  }

  private ProbeCapacities adaptive(int maximum) {
    return capacities(new BackgroundProbeCapacity.Adaptive(maximum));
  }

  private ProbeCapacities fixed(int capacity) {
    return capacities(new BackgroundProbeCapacity.Fixed(capacity));
  }

  private ProbeCapacities capacities(BackgroundProbeCapacity backgroundProbeCapacity) {
    return ProbeCapacities.builder()
        .clock(clock)
        .backgroundProbeCapacity(backgroundProbeCapacity)
        .onChange(changes::incrementAndGet)
        .build();
  }

  private static List<ProbeCapacities.CountedProbe> acquire(
      ProbeCapacities capacities, ProbeKind kind, int count) {
    var probes = new ArrayList<ProbeCapacities.CountedProbe>();
    for (var probe = 0; probe < count; probe++) {
      var counted = capacities.tryAcquire(kind);
      assertThat(counted).isPresent();
      probes.add(counted.get());
    }

    return probes;
  }

  // The server's cancel at the probe timeout arrives, then ffprobe exits.
  private static void timeOut(ProbeCapacities capacities, ProbeCapacities.CountedProbe probe) {
    capacities.probeTimedOut(probe);
    capacities.releaseTimedOut(probe);
  }

  // Produces 3 s media segments at the speed, advancing the clock by each segment's production
  // time.
  private void produce(ProbeCapacities capacities, UUID attempt, int segments, double speed) {
    var productionTime = Duration.ofNanos(Math.round(SEGMENT.toNanos() / speed));
    for (var segment = 0; segment < segments; segment++) {
      clock.advance(productionTime);
      capacities.mediaSegmentProduced(attempt, new ProductionTiming(SEGMENT, productionTime));
    }
  }

  // The server's view: it counts a probe from its send until its result arrives.
  private static final class ServerCounts {

    private final int playbackProbeCapacity;
    private final int backgroundProbeCapacity;
    private final List<ProbeKind> counted = new ArrayList<>();

    private ServerCounts(int playbackProbeCapacity, int backgroundProbeCapacity) {
      this.playbackProbeCapacity = playbackProbeCapacity;
      this.backgroundProbeCapacity = backgroundProbeCapacity;
    }

    private boolean fits(ProbeKind kind) {
      var playback = counted.stream().filter(ProbeKind.PLAYBACK::equals).count();
      var background = counted.size() - playback;
      var backgroundInUse = background + Math.max(0, playback - playbackProbeCapacity);
      if (kind == ProbeKind.PLAYBACK && playback < playbackProbeCapacity) {
        return true;
      }

      return backgroundInUse < backgroundProbeCapacity;
    }

    private void sent(ProbeKind kind) {
      counted.add(kind);
    }

    private void resultArrived(ProbeKind kind) {
      counted.remove(kind);
    }
  }
}
