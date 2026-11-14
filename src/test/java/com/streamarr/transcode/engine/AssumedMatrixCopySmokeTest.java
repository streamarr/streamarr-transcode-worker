package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.fixtures.DetectedCapabilities;
import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.fixtures.TopLevelBoxes;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("SmokeTest")
@DisplayName("Assumed Matrix Copy Smoke Tests")
class AssumedMatrixCopySmokeTest {

  private static final Duration LIMIT = Duration.ofSeconds(60);
  @TempDir Path root;

  @ParameterizedTest
  @EnumSource(AssumedSourceMatrix.class)
  @DisplayName(
      "Should preserve decoded pictures and name every SPS when a real H.264 copy assumes a matrix")
  void shouldPreserveDecodedPicturesAndNameEverySpsWhenARealH264CopyAssumesAMatrix(
      AssumedSourceMatrix matrix) throws Exception {
    var source = root.resolve("synthetic.mp4");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x64:rate=12:duration=2",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-tune",
            "zerolatency",
            "-profile:v",
            "baseline",
            "-g",
            "12",
            "-keyint_min",
            "12",
            "-sc_threshold",
            "0",
            "-bf",
            "0",
            "-x264-params",
            "repeat-headers=1:colorprim=undef:transfer=undef:colormatrix=undef",
            "-movflags",
            "empty_moov+default_base_moof+frag_keyframe",
            "-f",
            "mp4",
            source.toString()));
    var sourceBytes = Files.readAllBytes(source);
    var initialization = initialization(sourceBytes);
    var sourceEntry =
        CodecConfigurationReader.firstVideoSampleEntryOf(initialization).orElseThrow();
    var copy =
        VideoDecision.Copy.builder()
            .streamIndex(0)
            .codecString(CodecConfigurationReader.videoOf(sourceEntry).codecString())
            .keepsPacketBytes(true)
            .bitstreamRewrites(Set.of(BitstreamRewrite.NAME_ASSUMED_SOURCE_MATRIX))
            .assumedSourceMatrix(Optional.of(matrix))
            .plannedOutput(
                Optional.of(
                    PlannedCopyOutput.builder().dynamicRange(VideoDynamicRange.sdr()).build()))
            .build();
    var engine =
        FfmpegTranscodeEngine.builder()
            .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofMillis(250)))
            .capabilityService(DetectedCapabilities.realCapabilities())
            .launcher(new ProcessBuilderLauncher())
            .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT)
            .build();
    var sink = new RecordingSegmentSink();
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(source)
            .targetSegmentDuration(1)
            .width(64)
            .height(64)
            .bitrate(100_000L)
            .mediaSegmentCount(2)
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.REMUX)
                    .videoCodecFamily("h264")
                    .videoDecision(copy)
                    .audioDecision(AudioDecision.builder().mode(AudioMode.NONE).build())
                    .subtitleDecision(SubtitleDecisions.EXCLUDED)
                    .build())
            .build();
    var producer = engine.startProducer(request, sink, SegmentMemoryBudget.forSlots(1));
    try {
      assertThat(producer.outcome())
          .succeedsWithin(LIMIT)
          .isInstanceOf(AttemptOutcome.Completed.class);
    } finally {
      producer.stop();
    }

    var delivered = root.resolve("delivered.mp4");
    Files.write(delivered, sink.acceptedBytes());
    assertThat(decodedHashes(delivered)).containsExactlyElementsOf(decodedHashes(source));
    VideoOutputCheck.requireMatch(copy, new InitializationSegment(sink.contentOf("init.mp4")));
    var movie = Mp4Samples.read(sink.acceptedBytes());
    var parameterSets =
        movie.samplesOf(movie.videoTrack()).stream()
            .flatMap(sample -> parameterSets(sample.data()).stream())
            .toList();
    assertThat(parameterSets).isNotEmpty();
    assertThat(parameterSets)
        .allSatisfy(
            sps -> {
              var colour = AvcSequenceParameterSet.colourDescriptionOf(sps);
              assertThat(colour.matrix()).isEqualTo(matrix == AssumedSourceMatrix.BT709 ? 1 : 6);
              assertThat(colour.primaries()).isEqualTo(2);
              assertThat(colour.transfer()).isEqualTo(2);
            });
  }

  private static InitializationSegment initialization(byte[] media) {
    var end =
        TopLevelBoxes.of(media).stream()
            .filter(box -> box.type().equals("moov"))
            .findFirst()
            .orElseThrow()
            .end();
    return new InitializationSegment(Arrays.copyOf(media, end));
  }

  private static List<byte[]> parameterSets(ByteBuffer sample) {
    var bytes = sample.duplicate();
    var parameterSets = new ArrayList<byte[]>();
    while (bytes.hasRemaining()) {
      var length = bytes.getInt();
      var unit = new byte[length];
      bytes.get(unit);
      if ((unit[0] & 0x1F) == 7) {
        parameterSets.add(unit);
      }
    }

    return parameterSets;
  }

  private List<String> decodedHashes(Path media) throws Exception {
    var hashes = root.resolve(media.getFileName() + ".framemd5");
    ffmpeg(
        List.of(
            "-i",
            media.toString(),
            "-map",
            "0:v:0",
            "-fps_mode",
            "passthrough",
            "-pix_fmt",
            "yuv420p",
            "-f",
            "framemd5",
            hashes.toString()));
    return Files.readAllLines(hashes).stream()
        .filter(line -> !line.startsWith("#"))
        .map(line -> line.substring(line.lastIndexOf(',') + 1).strip())
        .toList();
  }

  private void ffmpeg(List<String> arguments) throws Exception {
    var command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
    command.addAll(arguments);
    var diagnostics = Files.createTempFile(root, "ffmpeg-", ".log");
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(diagnostics.toFile())
            .start();
    try {
      assertThat(process.waitFor(LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).as(Files.readString(diagnostics)).isZero();
    } finally {
      process.destroyForcibly().waitFor();
    }
  }
}
