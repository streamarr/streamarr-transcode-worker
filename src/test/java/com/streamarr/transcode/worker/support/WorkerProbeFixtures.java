package com.streamarr.transcode.worker.support;

import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING_CODEC_STRING;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING_LEVEL;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import build.buf.gen.streamarr.transcode.v1.AudioDecision;
import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.CancelProbeCommand;
import build.buf.gen.streamarr.transcode.v1.ContainerFormat;
import build.buf.gen.streamarr.transcode.v1.EncoderPreset;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.MediaSourceRef;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.StartProbeCommand;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.StopVariantCommand;
import build.buf.gen.streamarr.transcode.v1.SubtitleDecision;
import build.buf.gen.streamarr.transcode.v1.SubtitleMode;
import build.buf.gen.streamarr.transcode.v1.TranscodeDecision;
import build.buf.gen.streamarr.transcode.v1.TranscodeExecution;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.VariantSpec;
import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoCodecProfile;
import build.buf.gen.streamarr.transcode.v1.VideoDecision;
import build.buf.gen.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.TranscodeWorker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class WorkerProbeFixtures {

  private WorkerProbeFixtures() {}

  public static TranscodeWorker.TranscodeWorkerBuilder workerBuilder(Path root) {
    return workerBuilderWithoutFfprobe(root).ffprobe(fileContentsProducer());
  }

  public static TranscodeWorker.TranscodeWorkerBuilder workerBuilderWithoutFfprobe(Path root) {
    return TranscodeWorker.builder()
        .configuration(
            workerConfigurationBuilder()
                .availableSlots(2)
                .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, root))
                .build())
        .engine(engine(ScriptedProcessLauncher.running()));
  }

  public static ProbeRequest.Builder requestBuilder() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .setSource(sourceBuilder());
  }

  public static MediaSourceRef.Builder sourceBuilder() {
    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
        .setRelativeKey("movie.mkv");
  }

  public static ControlledProbeProcess.ControlledProbeProcessBuilder processBuilder() {
    return ControlledProbeProcess.builder()
        .stdout(
            """
            {"streams":[{"index":0,"codec_type":"video","codec_name":"h264"}]}
            """);
  }

  public static ProbeAttemptResult failure(ProbeRequest request, ProbeFailure failure) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setFailure(failure)
        .build();
  }

  public static void start(ScriptedWorkerRuntime.Connection connection, ProbeRequest request) {
    connection.deliver(
        EstablishWorkerSessionResponse.newBuilder()
            .setStartProbe(
                StartProbeCommand.newBuilder()
                    .setTarget(connection.registration().getWorker())
                    .setRequest(request))
            .build());
  }

  public static void cancel(ScriptedWorkerRuntime.Connection connection, ProbeRequest request) {
    connection.deliver(
        EstablishWorkerSessionResponse.newBuilder()
            .setCancelProbe(
                CancelProbeCommand.newBuilder()
                    .setTarget(connection.registration().getWorker())
                    .setProbeAttemptId(request.getProbeAttemptId()))
            .build());
  }

  public static void startVariant(ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    connection.deliver(
        EstablishWorkerSessionResponse.newBuilder()
            .setStartVariant(
                StartVariantCommand.newBuilder()
                    .setTarget(connection.registration().getWorker())
                    .setJob(job))
            .build());
  }

  public static void stopVariant(ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    connection.deliver(
        EstablishWorkerSessionResponse.newBuilder()
            .setStopVariant(
                StopVariantCommand.newBuilder()
                    .setTarget(connection.registration().getWorker())
                    .setJobAttemptId(job.getJobAttemptId()))
            .build());
  }

  /** A stream copy that the recording most tests replay as FFmpeg's output satisfies. */
  public static VariantJob.Builder variantJobBuilder() {
    return variantJobBuilder(TranscodeMode.TRANSCODE_MODE_REMUX);
  }

  /**
   * A job in the mode that the recording most tests replay as FFmpeg's output satisfies: its video
   * decision copies or encodes as the mode does and names the recording's codec string.
   */
  public static VariantJob.Builder variantJobBuilder(TranscodeMode mode) {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(sourceBuilder())
        .setDecision(
            TranscodeDecision.newBuilder()
                .setMode(mode)
                .setVideoCodecFamily("h264")
                .setVideo(recordingVideoFor(mode))
                .setAudio(AudioDecision.newBuilder().setMode(AudioMode.AUDIO_MODE_COPY))
                .setSubtitle(
                    SubtitleDecision.newBuilder().setMode(SubtitleMode.SUBTITLE_MODE_EXCLUDE))
                .setContainer(ContainerFormat.CONTAINER_FORMAT_FMP4))
        .setVariant(
            VariantSpec.newBuilder()
                .setVariantLabel("original")
                .setEncoderPreset(EncoderPreset.ENCODER_PRESET_FASTER))
        .setExecution(
            TranscodeExecution.newBuilder()
                .setTargetSegmentDurationSeconds(6)
                .setFramerate(24)
                .setMediaSegmentCount(11));
  }

  /** A stream copy whose video decision names the codec string that the copy recording declares. */
  public static VariantJob.Builder copyJobBuilderFor(Recording recording) {
    var job = variantJobBuilder();
    job.getDecisionBuilder().getVideoBuilder().setCodecString(recording.videoCodecString());
    return job;
  }

  /** A copy of H.264 video with the codec string. */
  public static VideoDecision.Builder copiedVideo(String codecString) {
    return VideoDecision.newBuilder()
        .setMode(VideoMode.VIDEO_MODE_COPY)
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)
        .setCodecString(codecString);
  }

  /** An H.264 High encode at the level and codec string of the encoded recording. */
  public static VideoDecision.Builder encodedRecordingVideo() {
    return VideoDecision.newBuilder()
        .setMode(VideoMode.VIDEO_MODE_ENCODE)
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)
        .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH)
        .setLevel(ENCODED_RECORDING_LEVEL)
        .setCodecString(ENCODED_RECORDING_CODEC_STRING);
  }

  private static VideoDecision.Builder recordingVideoFor(TranscodeMode mode) {
    return switch (mode) {
      case TRANSCODE_MODE_VIDEO_TRANSCODE, TRANSCODE_MODE_FULL_TRANSCODE -> encodedRecordingVideo();
      case TRANSCODE_MODE_REMUX,
          TRANSCODE_MODE_AUDIO_TRANSCODE,
          TRANSCODE_MODE_UNSPECIFIED,
          UNRECOGNIZED ->
          copiedVideo(ENCODED_RECORDING_CODEC_STRING);
    };
  }

  public static FfprobeExecutor fileContentsProducer() {
    return new FfprobeExecutor(
        new ObjectMapper(),
        command -> {
          try {
            return processBuilder()
                .stdout(Files.readString(Path.of(command.arguments().getLast())))
                .build();
          } catch (IOException exception) {
            throw new UncheckedIOException(exception);
          }
        });
  }
}
