package com.streamarr.transcode.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.engine.TranscodeCapabilityService.ProcessFactory;
import java.io.IOException;

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

  private static TranscodeCapabilityService detected(ProcessFactory processes) {
    var capabilities = new TranscodeCapabilityService("ffmpeg", processes);
    capabilities.detectCapabilities();
    assertThat(capabilities.isFfmpegAvailable()).as(capabilities.getUnavailableReason()).isTrue();
    return capabilities;
  }
}
