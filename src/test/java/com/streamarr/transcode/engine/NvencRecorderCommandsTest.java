package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the FFmpeg commands that src/test/fmp4-recorder/record-nvenc.sh runs on an NVIDIA GPU host
 * to the worker's own command for the same job, so that the NVENC recordings record the worker's
 * recipe and its deviations are only the ones each recording names.
 */
@Tag("UnitTest")
@DisplayName("NVENC Recorder Command Tests")
class NvencRecorderCommandsTest {

  private static final Path RECORDER = Path.of("src", "test", "fmp4-recorder", "record-nvenc.sh");
  private static final Path COMMITTED_RECORDINGS =
      Path.of("src", "test", "resources", "nvenc", "recordings.json");
  // The quiet, non-interactive logging that only the recording adds.
  private static final List<String> RECORDING_ONLY_OPTIONS =
      List.of("-hide_banner", "-nostdin", "-loglevel", "error");
  private static final Duration WORKER_FRAGMENTATION_TARGET = Duration.ofMillis(250);
  private static final AudioDecision NO_AUDIO =
      AudioDecision.builder().mode(AudioMode.NONE).codec(null).channels(0).bitrate(0L).build();

  // The worker gives both NVENC encoders the extended GOP, so the extended recordings that force
  // every listed start run the worker's own command.
  static Stream<PlannedRecording> recordingsOfTheWorkersCommand() {
    return plannedRecordings().stream().filter(PlannedRecording::runsTheWorkersCommand);
  }

  static Stream<PlannedRecording> recordingsThatDepartFromTheWorkersCommand() {
    return plannedRecordings().stream().filter(recording -> !recording.runsTheWorkersCommand());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordingsOfTheWorkersCommand")
  @DisplayName(
      "Should plan the worker's own command, without the recording's logging options, when a"
          + " recording takes the extended GOP and forces every listed start")
  void
      shouldPlanTheWorkersOwnCommandWithoutTheRecordingsLoggingOptionsWhenARecordingTakesTheExtendedGopAndForcesEveryListedStart(
          PlannedRecording recording) {
    assertThat(recording.argumentsWithoutRecordingOnlyOptions())
        .isEqualTo(workerCommandFor(recording));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordingsThatDepartFromTheWorkersCommand")
  @DisplayName(
      "Should differ from the worker's command only in the GOP and the forced keyframe times when a"
          + " recording takes the floored GOP or leaves a forced keyframe out")
  void
      shouldDifferFromTheWorkersCommandOnlyInTheGopAndTheForcedKeyframeTimesWhenARecordingTakesTheFlooredGopOrLeavesAForcedKeyframeOut(
          PlannedRecording recording) {
    var expected = new ArrayList<>(workerCommandFor(recording));
    replaceValue(expected, "-g:v:0", recording.job().get("gopFrames"));
    replaceValue(expected, "-keyint_min:v:0", recording.job().get("gopFrames"));
    replaceValue(expected, "-force_key_frames:0", recording.job().get("forcedKeyframeTimes"));

    assertThat(recording.argumentsWithoutRecordingOnlyOptions()).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "Should plan each NVENC encoder at every default-ladder rung from the start, as a replacement"
          + " attempt and with a missed forced keyframe when the recorder runs with its defaults")
  void
      shouldPlanEachNvencEncoderAtEveryDefaultLadderRungFromTheStartAsAReplacementAttemptAndWithAMissedForcedKeyframeWhenTheRecorderRunsWithItsDefaults() {
    var variants =
        List.of(
            "first", "replacement", "extended-first", "extended-replacement", "extended-missed");

    assertThat(plannedRecordings())
        .filteredOn(recording -> recording.job().get("preset").equals("p4"))
        .filteredOn(recording -> recording.job().get("range").equals("sdr"))
        .extracting(PlannedRecording::name)
        .isEqualTo(
            Stream.of("h264_nvenc", "hevc_nvenc")
                .flatMap(
                    encoder ->
                        Stream.of("1080p", "720p", "480p", "360p")
                            .flatMap(
                                rung -> variants.stream().map(v -> encoder + "-" + rung + "-" + v)))
                .toList());
  }

  @Test
  @DisplayName(
      "Should plan every other NVENC preset on the preset scale at 1080p with the extended GOP when"
          + " the recorder runs with its defaults")
  void
      shouldPlanEveryOtherNvencPresetOnThePresetScaleAt1080pWithTheExtendedGopWhenTheRecorderRunsWithItsDefaults() {
    var otherPresets =
        Arrays.stream(EncoderPreset.values())
            .map(EncoderPreset::nvencPreset)
            .filter(preset -> !preset.equals(EncoderPreset.FASTER.nvencPreset()))
            .distinct()
            .sorted()
            .toList();
    var variants = List.of("extended-first", "extended-replacement", "extended-missed");

    assertThat(plannedRecordings())
        .filteredOn(recording -> !recording.job().get("preset").equals("p4"))
        .extracting(PlannedRecording::name)
        .isEqualTo(
            Stream.of("h264_nvenc", "hevc_nvenc")
                .flatMap(
                    encoder ->
                        otherPresets.stream()
                            .flatMap(
                                preset ->
                                    variants.stream()
                                        .map(v -> encoder + "-1080p-" + preset + "-" + v)))
                .toList());
  }

  @Test
  @DisplayName(
      "Should plan hevc_nvenc in Main 10 at every default-ladder rung from 10-bit BT.709, PQ, HLG and"
          + " Dolby Vision sources with the extended GOP when the recorder runs with its defaults")
  void
      shouldPlanHevcNvencInMain10AtEveryDefaultLadderRungFromTenBitBt709PqHlgAndDolbyVisionSourcesWithTheExtendedGopWhenTheRecorderRunsWithItsDefaults() {
    var variants = List.of("extended-first", "extended-replacement", "extended-missed");
    var tenBit =
        plannedRecordings().stream()
            .filter(recording -> !recording.job().get("range").equals("sdr"))
            .toList();

    assertThat(tenBit)
        .extracting(PlannedRecording::name)
        .isEqualTo(
            Stream.of("1080p", "720p", "480p", "360p")
                .flatMap(
                    rung ->
                        Stream.of("sdr10", "pq", "hlg", "dolby-vision")
                            .flatMap(
                                range ->
                                    variants.stream()
                                        .map(v -> "hevc_nvenc-" + rung + "-" + range + "-" + v)))
                .toList());
    assertThat(tenBit)
        .allSatisfy(
            recording -> {
              assertThat(recording.job()).containsEntry("bitDepth", "10");
              assertThat(recording.job().get("expectedCodecString")).startsWith("hvc1.2.4.L");
            });
  }

  @Test
  @DisplayName(
      "Should plan one segment list with a merged segment for every recording when the recorder"
          + " runs with its defaults")
  void
      shouldPlanOneSegmentListWithAMergedSegmentForEveryRecordingWhenTheRecorderRunsWithItsDefaults() {
    var recordings = plannedRecordings();

    assertThat(recordings)
        .extracting(NvencRecorderCommandsTest::segmentStartSlotsOf)
        .containsOnly(List.of(0L, 1L, 2L, 3L, 5L));
    assertThat(recordings)
        .extracting(recording -> recording.job().get("mediaSegmentCount"))
        .containsOnly("5");
  }

  @Test
  @DisplayName(
      "Should hold a committed recording of exactly the command the recorder plans when a change"
          + " to the worker's command changes the planned commands")
  void
      shouldHoldACommittedRecordingOfExactlyTheCommandTheRecorderPlansWhenAChangeToTheWorkersCommandChangesThePlannedCommands() {
    var recorded = new LinkedHashMap<String, List<String>>();
    for (var recording : committedRecordings().get("recordings")) {
      recorded.put(
          recording.get("name").asString(),
          recording.get("ffmpegArguments").valueStream().map(JsonNode::asString).toList());
    }

    var planned = new LinkedHashMap<String, List<String>>();
    plannedRecordings().forEach(recording -> planned.put(recording.name(), recording.arguments()));

    assertThat(recorded).isEqualTo(planned);
  }

  private static List<String> workerCommandFor(PlannedRecording recording) {
    var pipeline =
        new FfmpegCommandBuilder("ffmpeg", WORKER_FRAGMENTATION_TARGET)
            .buildPipeline(jobPlannedFor(recording));
    return switch (pipeline) {
      case FfmpegPipeline.SingleProcess(var command) -> command;
      case FfmpegPipeline.RpuMovingPipe _ ->
          throw new AssertionError("A recorded source needs one FFmpeg, not " + pipeline);
    };
  }

  private static TranscodeJob jobPlannedFor(PlannedRecording recording) {
    var job = recording.job();
    var encoder = job.get("encoder");
    var encode =
        NvencRecordedEncodes.encodeOf(encoder, job.get("range"))
            .tier(tierOf(job.get("tier")))
            .level(Integer.parseInt(job.get("level")))
            .codecString(job.get("expectedCodecString"))
            .sourcePacketsCarryPresentationTimes(true)
            .build();
    var decision =
        TranscodeDecision.builder()
            .transcodeMode(TranscodeMode.FULL_TRANSCODE)
            .videoDecision(encode)
            .audioDecision(NO_AUDIO)
            .subtitleDecision(SubtitleDecisions.EXCLUDED)
            .build();
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of(sourceOf(job.get("range"))))
            .startSequenceNumber(Integer.parseInt(job.get("startSequenceNumber")))
            .mediaSegmentCount(Integer.parseInt(job.get("mediaSegmentCount")))
            .segmentStartSlots(segmentStartSlotsOf(recording))
            .targetSegmentDuration(6)
            .outputFrameRate(Optional.of(new OutputFrameRate(24_000, 1001)))
            .transcodeDecision(decision)
            .encoderPreset(scalePresetOf(job.get("preset")))
            .height(Integer.parseInt(job.get("height")))
            .bitrate(Long.parseLong(job.get("bitrate")))
            .build();
    // The recorder runs the GPU reshape of the Dolby Vision source, as the worker does once the
    // encoder passed its GPU reshape test.
    return TranscodeJob.builder()
        .request(request)
        .videoEncoder(encoder)
        .gpuDolbyVisionReshapeVerified(
            job.get("range").equals(NvencRecordedEncodes.DOLBY_VISION_RANGE))
        .build();
  }

  // The recorder's H.264 source, or its HEVC source of a 10-bit range.
  private static String sourceOf(String range) {
    return range.equals("sdr") ? "/work/source.mp4" : "/work/source-" + range + ".mp4";
  }

  // The first scale name whose NVENC preset the recording takes.
  private static EncoderPreset scalePresetOf(String nvencPreset) {
    return Arrays.stream(EncoderPreset.values())
        .filter(preset -> preset.nvencPreset().equals(nvencPreset))
        .findFirst()
        .orElseThrow(() -> new AssertionError("The scale has no NVENC preset " + nvencPreset));
  }

  private static List<Long> segmentStartSlotsOf(PlannedRecording recording) {
    return Arrays.stream(recording.job().get("segmentStartSlots").split(","))
        .map(Long::valueOf)
        .toList();
  }

  private static Optional<VideoCodecTier> tierOf(String tier) {
    return switch (tier) {
      case "" -> Optional.empty();
      case "main" -> Optional.of(VideoCodecTier.MAIN);
      case "high" -> Optional.of(VideoCodecTier.HIGH);
      default -> throw new AssertionError("The recorder plans no tier " + tier);
    };
  }

  private static void replaceValue(List<String> command, String option, String value) {
    var index = command.indexOf(option);
    assertThat(index).as("the worker's %s", option).isNotNegative();
    command.set(index + 1, value);
  }

  private static JsonNode committedRecordings() {
    return JsonMapper.builder().build().readTree(COMMITTED_RECORDINGS.toFile());
  }

  private static List<PlannedRecording> plannedRecordings() {
    try {
      var process =
          new ProcessBuilder("bash", RECORDER.toString(), "--print-commands")
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("the recorder exits").isTrue();
      assertThat(process.exitValue()).as("the recorder's exit status").isZero();
      return Arrays.stream(output.split("\n\n")).map(PlannedRecording::parse).toList();
    } catch (IOException e) {
      throw new AssertionError("The recorder could not run", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while the recorder ran", e);
    }
  }

  /** One recording that the recorder plans: its name, its job and its FFmpeg command. */
  record PlannedRecording(String name, Map<String, String> job, List<String> arguments) {

    // A block holds "# NAME", then "# key=value" lines, then one argument per line.
    static PlannedRecording parse(String block) {
      var lines = block.strip().lines().toList();
      var job = new LinkedHashMap<String, String>();
      lines.stream()
          .skip(1)
          .takeWhile(line -> line.startsWith("# "))
          .map(line -> line.substring(2))
          .forEach(
              line ->
                  job.put(
                      line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1)));
      var arguments = lines.stream().filter(line -> !line.startsWith("# ")).toList();
      return new PlannedRecording(lines.getFirst().substring(2), job, arguments);
    }

    boolean runsTheWorkersCommand() {
      return job.get("recipe").equals("extended") && job.get("skippedForcedTime").isEmpty();
    }

    List<String> argumentsWithoutRecordingOnlyOptions() {
      var command = new ArrayList<>(arguments);
      var start = Collections.indexOfSubList(command, RECORDING_ONLY_OPTIONS);
      assertThat(start).as("the recording's logging options in %s", name).isEqualTo(1);
      command.subList(start, start + RECORDING_ONLY_OPTIONS.size()).clear();
      return command;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
