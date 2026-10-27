package com.streamarr.transcode.fakes;

import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.FfmpegRecordings;
import com.streamarr.transcode.engine.ProcessLauncher;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Launches a scripted process in place of FFmpeg for each job attempt and keeps it, so a test can
 * observe what happened to one attempt's FFmpeg.
 */
public final class ScriptedProcessLauncher implements ProcessLauncher {

  private final Script script;
  private final Map<UUID, List<Launch>> launches = new ConcurrentHashMap<>();

  public ScriptedProcessLauncher(Script script) {
    this.script = script;
  }

  /** FFmpeg that writes nothing until it is asked to quit, and then exits cleanly. */
  public static ScriptedProcessLauncher running() {
    return new ScriptedProcessLauncher(_ -> runningProcessBuilder().build());
  }

  /** FFmpeg that writes the recording to its standard output and exits cleanly. */
  public static ScriptedProcessLauncher writing(String recording) {
    return new ScriptedProcessLauncher(
        _ -> ScriptedProcess.builder().output(FfmpegRecordings.bytesOf(recording)).build());
  }

  /** A process that holds its whole output back until {@code q} arrives, then exits cleanly. */
  public static ScriptedProcess.ScriptedProcessBuilder runningProcessBuilder() {
    return ScriptedProcess.builder()
        .output(FfmpegRecordings.bytesOf(ENCODED_RECORDING))
        .pauseAfter(0)
        .resumesOnQuit(true)
        .exitTiming(ExitTiming.AT_QUIT);
  }

  @Override
  public Process launch(List<String> command, UUID jobAttemptId) throws IOException {
    var process = script.launch(jobAttemptId);
    launches
        .computeIfAbsent(jobAttemptId, _ -> new CopyOnWriteArrayList<>())
        .add(new Launch(List.copyOf(command), process));
    return process;
  }

  public boolean hasLaunched(UUID jobAttemptId) {
    return launches.containsKey(jobAttemptId);
  }

  public boolean hasLaunchedAny() {
    return !launches.isEmpty();
  }

  /** The process launched last for the job attempt: the one whose output the worker reads. */
  public ScriptedProcess process(UUID jobAttemptId) {
    return recordedLaunches(jobAttemptId).getLast().process();
  }

  /** The command FFmpeg was launched with for the job attempt, which launched one FFmpeg. */
  public List<String> command(UUID jobAttemptId) {
    var attemptLaunches = recordedLaunches(jobAttemptId);
    assertThat(attemptLaunches).as("FFmpegs launched for job attempt %s", jobAttemptId).hasSize(1);
    return attemptLaunches.getFirst().command();
  }

  /** The commands of every FFmpeg launched for the job attempt, in launch order. */
  public List<List<String>> commands(UUID jobAttemptId) {
    return recordedLaunches(jobAttemptId).stream().map(Launch::command).toList();
  }

  private List<Launch> recordedLaunches(UUID jobAttemptId) {
    var attemptLaunches = launches.get(jobAttemptId);
    assertThat(attemptLaunches).as("FFmpeg launched for job attempt %s", jobAttemptId).isNotNull();
    return attemptLaunches;
  }

  /** Stands in for FFmpeg when a job attempt launches it. */
  @FunctionalInterface
  public interface Script {
    ScriptedProcess launch(UUID jobAttemptId) throws IOException;
  }

  private record Launch(List<String> command, ScriptedProcess process) {}
}
