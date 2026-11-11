package com.streamarr.transcode.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.transcode.engine.EncodeFeature;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.engine.TranscodeCapabilityService.ProcessFactory;
import com.streamarr.transcode.engine.WorkerVerifiedEncoder;
import java.io.IOException;
import java.util.Set;

/**
 * What the worker detects with the FFmpeg on the path. One detection starts FFmpeg more than ten
 * times, so the smoke tests of a JVM share a detected service wherever no test can change it: one
 * that offers no hardware encoder, the only kind that a worker withdraws and tests again.
 */
public final class DetectedCapabilities {

  private static TranscodeCapabilityService real;
  private static TranscodeCapabilityService software;

  private DetectedCapabilities() {}

  /** The encoders of the host. A host that offers a hardware encoder is detected at every call. */
  public static synchronized TranscodeCapabilityService realCapabilities() {
    if (real == null || real.getHardwareEncodingCapability().available()) {
      real = detected(ProcessBuilder::start);
    }

    return real;
  }

  /** Lists no hardware encoder, so that each codec family runs its software encoder. */
  public static synchronized TranscodeCapabilityService softwareCapabilities() {
    if (software == null) {
      software = detected(DetectedCapabilities::startListingNoEncoder);
    }

    return software;
  }

  private static Process startListingNoEncoder(ProcessBuilder command) throws IOException {
    if (command.command().contains("-encoders")) {
      return new ProcessBuilder("true").start();
    }

    return command.start();
  }

  // A test encode that fails or outlasts its deadline drops its encoder or its encode feature from
  // a detection without an error. A shared detection that lacks one would fail every later test
  // that needs it, far from the cause. So a detection short of what the locked FFmpeg gives fails
  // the test that asked for it, and the next test detects again.
  private static TranscodeCapabilityService detected(ProcessFactory processes) {
    var capabilities = new TranscodeCapabilityService("ffmpeg", processes);
    capabilities.detectCapabilities();
    assertThat(capabilities.isFfmpegAvailable()).as(capabilities.getUnavailableReason()).isTrue();
    assertThat(capabilities.softwareEncoders())
        .as("the software encoders and encode features whose test encodes passed in this detection")
        .extracting(encoder -> encoder.backend().encoder(), WorkerVerifiedEncoder::encodeFeatures)
        .containsExactlyInAnyOrder(
            tuple("libx264", Set.of()),
            tuple("libx265", Set.of(EncodeFeature.values())),
            tuple("libsvtav1", Set.of(EncodeFeature.values())));
    return capabilities;
  }
}
