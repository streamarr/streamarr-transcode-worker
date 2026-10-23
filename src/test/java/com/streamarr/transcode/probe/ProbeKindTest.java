package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbePriority;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Probe Kind Tests")
class ProbeKindTest {

  @ParameterizedTest(name = "{0}, scan {1} -> {2}")
  @CsvSource({
    "PROBE_PRIORITY_PLAYBACK, false, PLAYBACK",
    "PROBE_PRIORITY_BACKGROUND, false, BACKGROUND",
    "PROBE_PRIORITY_UNSPECIFIED, false, BACKGROUND",
    "PROBE_PRIORITY_PLAYBACK, true, KEYFRAME_PACKET_SCAN",
    "PROBE_PRIORITY_BACKGROUND, true, KEYFRAME_PACKET_SCAN",
  })
  @DisplayName("Should take the kind from the scan flag before the priority when a probe arrives")
  void shouldTakeTheKindFromTheScanFlagBeforeThePriorityWhenAProbeArrives(
      ProbePriority priority, boolean scanKeyframePackets, ProbeKind expected) {
    var request =
        ProbeRequest.newBuilder()
            .setPriority(priority)
            .setScanKeyframePackets(scanKeyframePackets)
            .build();

    assertThat(ProbeKind.of(request)).isEqualTo(expected);
  }
}
