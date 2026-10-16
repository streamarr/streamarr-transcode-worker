package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.fakes.ScriptedProcess;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Probes with ffprobe output that the pinned worker image recorded into {@code
 * src/test/resources/probe}, or with output a test writes itself. The request asks for a packet
 * scan, which finds no packets, so a probe never reads the source file.
 */
final class RecordedFfprobe {

  private static final Path SOURCE = Path.of("/media/source");

  private RecordedFfprobe() {}

  static ProbeMediaInfo probeFixture(String fixture) {
    return probeOutput(fixture(fixture));
  }

  static ProbeStreamInfo streamOf(String fixture, int index) {
    return probeFixture(fixture).getStreamsList().stream()
        .filter(stream -> stream.getIndex() == index)
        .findFirst()
        .orElseThrow(() -> new AssertionError(fixture + " has no stream " + index));
  }

  static ProbeMediaInfo probeOutput(String ffprobeOutput) {
    var result = probeResult(ffprobeOutput);
    assertThat(result.hasMedia()).as("the probe succeeds").isTrue();
    return result.getMedia();
  }

  static ProbeAttemptResult probeResult(String ffprobeOutput) {
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                ScriptedProcess.builder()
                    .output(
                        switch (command.tool()) {
                          case FFPROBE -> ffprobeOutput.getBytes(StandardCharsets.UTF_8);
                          case FFMPEG -> new byte[0];
                        })
                    .build());
    return executor.probe(SOURCE, request()).result();
  }

  static String fixture(String fixture) {
    try (InputStream recorded =
        RecordedFfprobe.class.getResourceAsStream("/probe/" + fixture + ".json")) {
      assertThat(recorded).as("recorded fixture " + fixture).isNotNull();
      return new String(recorded.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ProbeRequest request() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .setScanKeyframePackets(true)
        .build();
  }
}
