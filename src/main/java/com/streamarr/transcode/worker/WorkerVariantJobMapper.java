package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.TranscodeExecution;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.engine.AudioDecision;
import com.streamarr.transcode.engine.AudioMode;
import com.streamarr.transcode.engine.EncoderPreset;
import com.streamarr.transcode.engine.SubtitleDecision;
import com.streamarr.transcode.engine.SubtitleMode;
import com.streamarr.transcode.engine.TranscodeDecision;
import com.streamarr.transcode.engine.TranscodeMode;
import com.streamarr.transcode.engine.TranscodeRequest;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

// Domain and protobuf contracts intentionally share simple names.
final class WorkerVariantJobMapper {

  private final WorkerMediaSourceResolver sourceResolver;

  WorkerVariantJobMapper(WorkerMediaSourceResolver sourceResolver) {
    this.sourceResolver = sourceResolver;
  }

  /**
   * @throws InvalidJobSpecificationException when the job names no transcode, audio or subtitle
   *     mode, or when its video decision is missing, contradicts the rest of the job, or lacks a
   *     value that the worker forces or checks
   */
  TranscodeRequest map(VariantJob job) {
    var variant = job.getVariant();
    var execution = job.getExecution();
    return TranscodeRequest.builder()
        .sessionId(fromProto(job.getStreamSessionId()))
        .attemptId(fromProto(job.getJobAttemptId()))
        .sourcePath(sourceResolver.resolve(job.getSource()))
        .targetSegmentDuration(execution.getTargetSegmentDurationSeconds())
        .framerate(execution.getFramerate())
        .transcodeDecision(decision(job.getDecision()))
        .encoderPreset(encoderPresetOf(variant.getEncoderPreset()).orElse(null))
        .width(variant.getWidth())
        .height(variant.getHeight())
        .bitrate(variant.getBitrateBitsPerSecond())
        .variantLabel(variant.getVariantLabel())
        // The start sequence number decides the seek of a job without a sub-second seek position,
        // so seek_position_seconds, which the server sets to that segment's boundary, is not read.
        .startSequenceNumber(execution.getStartSequenceNumber())
        .mediaSegmentCount(execution.getMediaSegmentCount())
        .seekPosition(seekPosition(execution))
        .build();
  }

  private static Optional<Duration> seekPosition(TranscodeExecution execution) {
    if (!execution.hasSeekPosition()) {
      return Optional.empty();
    }

    var position = execution.getSeekPosition();
    return Optional.of(Duration.ofSeconds(position.getSeconds(), position.getNanos()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private TranscodeDecision decision(
      build.buf.gen.streamarr.transcode.v1.TranscodeDecision decision) {
    var mode = mode(decision.getMode());
    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily(codecFamily(decision))
        .videoDecision(VideoDecisions.of(decision, mode))
        .audioDecision(audio(decision.getAudio()))
        .subtitleDecision(subtitle(decision.getSubtitle()))
        .build();
  }

  // The video decision's codec family replaces the string; while the server sends both, they agree.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static String codecFamily(
      build.buf.gen.streamarr.transcode.v1.TranscodeDecision decision) {
    var legacy = decision.getVideoCodecFamily();
    var named = VideoDecisions.codecFamilyName(decision.getVideo().getCodecFamily());
    if (named.isEmpty()) {
      return legacy;
    }

    if (!legacy.isEmpty() && !legacy.equals(named.orElseThrow())) {
      throw new InvalidJobSpecificationException(
          "The video decision names codec family "
              + named.orElseThrow()
              + " but the job names "
              + legacy);
    }

    return named.orElseThrow();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private AudioDecision audio(build.buf.gen.streamarr.transcode.v1.AudioDecision audio) {
    return AudioDecision.builder()
        .mode(audioMode(audio.getMode()))
        .codec(audio.hasCodec() ? audio.getCodec() : null)
        .channels(audio.getChannels())
        .bitrate(audio.getBitrateBitsPerSecond())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private SubtitleDecision subtitle(
      build.buf.gen.streamarr.transcode.v1.SubtitleDecision subtitle) {
    return new SubtitleDecision(
        subtitleMode(subtitle.getMode()),
        subtitle.hasCodec() ? Optional.of(subtitle.getCodec()) : Optional.empty(),
        subtitle.hasStreamIndex() ? OptionalInt.of(subtitle.getStreamIndex()) : OptionalInt.empty(),
        subtitle.hasLanguage() ? Optional.of(subtitle.getLanguage()) : Optional.empty());
  }

  // Whether the job encodes video, by the engine's rule; a job that names no mode encodes none.
  static boolean encodesVideo(VariantJob job) {
    return modeOf(job.getDecision().getMode()).map(TranscodeMode::encodesVideo).orElse(false);
  }

  static boolean hasEncoderPreset(VariantJob job) {
    return encoderPresetOf(job.getVariant().getEncoderPreset()).isPresent();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<EncoderPreset> encoderPresetOf(
      build.buf.gen.streamarr.transcode.v1.EncoderPreset preset) {
    return switch (preset) {
      case ENCODER_PRESET_ULTRAFAST -> Optional.of(EncoderPreset.ULTRAFAST);
      case ENCODER_PRESET_SUPERFAST -> Optional.of(EncoderPreset.SUPERFAST);
      case ENCODER_PRESET_VERYFAST -> Optional.of(EncoderPreset.VERYFAST);
      case ENCODER_PRESET_FASTER -> Optional.of(EncoderPreset.FASTER);
      case ENCODER_PRESET_FAST -> Optional.of(EncoderPreset.FAST);
      case ENCODER_PRESET_MEDIUM -> Optional.of(EncoderPreset.MEDIUM);
      case ENCODER_PRESET_SLOW -> Optional.of(EncoderPreset.SLOW);
      case ENCODER_PRESET_SLOWER -> Optional.of(EncoderPreset.SLOWER);
      case ENCODER_PRESET_VERYSLOW -> Optional.of(EncoderPreset.VERYSLOW);
      case ENCODER_PRESET_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static TranscodeMode mode(build.buf.gen.streamarr.transcode.v1.TranscodeMode mode) {
    return modeOf(mode)
        .orElseThrow(() -> new InvalidJobSpecificationException("Transcode mode is required"));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<TranscodeMode> modeOf(
      build.buf.gen.streamarr.transcode.v1.TranscodeMode mode) {
    return switch (mode) {
      case TRANSCODE_MODE_REMUX -> Optional.of(TranscodeMode.REMUX);
      case TRANSCODE_MODE_AUDIO_TRANSCODE -> Optional.of(TranscodeMode.AUDIO_TRANSCODE);
      case TRANSCODE_MODE_VIDEO_TRANSCODE -> Optional.of(TranscodeMode.VIDEO_TRANSCODE);
      case TRANSCODE_MODE_FULL_TRANSCODE -> Optional.of(TranscodeMode.FULL_TRANSCODE);
      case TRANSCODE_MODE_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private AudioMode audioMode(build.buf.gen.streamarr.transcode.v1.AudioMode mode) {
    return switch (mode) {
      case AUDIO_MODE_COPY -> AudioMode.COPY;
      case AUDIO_MODE_TRANSCODE -> AudioMode.TRANSCODE;
      case AUDIO_MODE_NONE -> AudioMode.NONE;
      case AUDIO_MODE_UNSPECIFIED, UNRECOGNIZED ->
          throw new InvalidJobSpecificationException("Audio mode is required");
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private SubtitleMode subtitleMode(build.buf.gen.streamarr.transcode.v1.SubtitleMode mode) {
    return switch (mode) {
      case SUBTITLE_MODE_EXCLUDE -> SubtitleMode.EXCLUDE;
      case SUBTITLE_MODE_BURN_IN -> SubtitleMode.BURN_IN;
      case SUBTITLE_MODE_SIDECAR -> SubtitleMode.SIDECAR;
      case SUBTITLE_MODE_HLS -> SubtitleMode.HLS;
      case SUBTITLE_MODE_EMBED -> SubtitleMode.EMBED;
      case SUBTITLE_MODE_UNSPECIFIED, UNRECOGNIZED ->
          throw new InvalidJobSpecificationException("Subtitle mode is required");
    };
  }
}
