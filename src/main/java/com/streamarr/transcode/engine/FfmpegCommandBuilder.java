package com.streamarr.transcode.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class FfmpegCommandBuilder {

  @NonNull private final String ffmpegPath;
  @NonNull private final Duration fragmentationTarget;

  private static final Set<String> FIXED_GOP_ENCODERS =
      Set.of(
          "libsvtav1",
          "h264_nvenc",
          "hevc_nvenc",
          "av1_nvenc",
          "h264_qsv",
          "hevc_qsv",
          "av1_qsv",
          "h264_amf",
          "hevc_amf",
          "av1_amf",
          "h264_rkmpp",
          "hevc_rkmpp");

  private static final Set<String> FORCE_KEYFRAME_ENCODERS =
      Set.of("libx264", "libx265", "h264_vaapi", "hevc_vaapi", "av1_vaapi");

  static final List<String> MP4_MOVFLAGS =
      List.of("cmaf", "delay_moov", "skip_trailer", "frag_keyframe", "frag_discont");

  public List<String> buildCommand(TranscodeJob job) {
    var cmd = new ArrayList<String>();
    var decision = job.request().transcodeDecision();
    var mode = decision.transcodeMode();

    addInputArgs(cmd, job.request());
    addStreamSelection(cmd, decision.audioDecision(), decision.subtitleDecision());
    addCommonFlags(cmd);
    addCodecArgs(cmd, job);

    if (mode == TranscodeMode.VIDEO_TRANSCODE || mode == TranscodeMode.FULL_TRANSCODE) {
      addFrameRateArgs(cmd, job.request());
      addKeyframeArgs(cmd, job);
    }

    addFragmentedMp4Output(cmd);

    return List.copyOf(cmd);
  }

  private void addInputArgs(List<String> cmd, TranscodeRequest request) {
    cmd.add(ffmpegPath);
    cmd.add("-y");

    if (request.seekPosition() > 0) {
      cmd.addAll(List.of("-ss", String.valueOf(request.seekPosition())));
    }

    cmd.addAll(List.of("-i", request.sourcePath().toString()));
  }

  private void addStreamSelection(
      List<String> cmd, AudioDecision audio, SubtitleDecision subtitle) {
    cmd.addAll(List.of("-map", "0:v:0"));
    if (audio.mode() != AudioMode.NONE) {
      cmd.addAll(List.of("-map", "0:a:0"));
    }

    if (subtitle.mode() == SubtitleMode.EXCLUDE) {
      cmd.addAll(List.of("-map", "-0:s"));
    }
  }

  private void addCommonFlags(List<String> cmd) {
    cmd.addAll(
        List.of(
            "-map_metadata",
            "-1",
            "-map_chapters",
            "-1",
            "-copyts",
            "-avoid_negative_ts",
            "disabled",
            "-start_at_zero",
            "-max_muxing_queue_size",
            "128"));
  }

  private void addCodecArgs(List<String> cmd, TranscodeJob job) {
    var decision = job.request().transcodeDecision();
    var mode = decision.transcodeMode();

    if (mode == TranscodeMode.REMUX || mode == TranscodeMode.AUDIO_TRANSCODE) {
      cmd.addAll(List.of("-c:v", "copy"));
      addAudioArgs(cmd, decision.audioDecision());
      return;
    }

    cmd.addAll(List.of("-c:v", job.videoEncoder()));
    addScaleAndBitrateArgs(cmd, job);
    addAudioArgs(cmd, decision.audioDecision());
  }

  private void addAudioArgs(List<String> cmd, AudioDecision audio) {
    if (audio.mode() == AudioMode.NONE) {
      return;
    }

    if (audio.mode() == AudioMode.COPY) {
      cmd.addAll(copiedAudioArgs(audio.codec()));
      return;
    }

    cmd.addAll(List.of("-c:a", audio.codec()));
    cmd.addAll(List.of("-ac", String.valueOf(audio.channels())));
    cmd.addAll(List.of("-b:a", audio.bitrate() / 1000 + "k"));
  }

  private static List<String> copiedAudioArgs(String codec) {
    // delay_moov stops the mp4 muxer from inserting this filter itself, and a copy of the ADTS
    // AAC that MPEG-TS sources carry fails without it. Raw AAC passes through unchanged.
    if ("aac".equals(codec)) {
      return List.of("-c:a", "copy", "-bsf:a", "aac_adtstoasc");
    }

    return List.of("-c:a", "copy");
  }

  private void addScaleAndBitrateArgs(List<String> cmd, TranscodeJob job) {
    var request = job.request();
    cmd.addAll(List.of("-vf", "scale=-2:" + request.height()));
    var bitrate = String.valueOf(request.bitrate());
    // SVT-AV1 supports a bitrate cap only in CRF mode. Its default CRF is 35.
    if ("libsvtav1".equals(job.videoEncoder())) {
      cmd.addAll(
          List.of("-crf", "35", "-maxrate", bitrate, "-svtav1-params", "mbr-overshoot-pct=0"));
      return;
    }

    cmd.addAll(
        List.of(
            "-b:v", bitrate,
            "-maxrate", bitrate,
            "-bufsize", String.valueOf(request.bitrate() * 2)));
  }

  // The rate the frame-count GOP is computed from; without -fps_mode FFmpeg then emits the
  // constant-rate frames the GOP counts, and -copyts keeps a seek's first timestamp.
  private void addFrameRateArgs(List<String> cmd, TranscodeRequest request) {
    cmd.addAll(List.of("-r:v:0", String.valueOf(request.framerate())));
  }

  private void addKeyframeArgs(List<String> cmd, TranscodeJob job) {
    var encoder = job.videoEncoder();

    cmd.addAll(List.of("-forced-idr", "1"));

    if (FIXED_GOP_ENCODERS.contains(encoder)) {
      addGopSizeArgs(cmd, job);
      return;
    }

    if (FORCE_KEYFRAME_ENCODERS.contains(encoder)) {
      addForceKeyframeExprArgs(cmd, job);
    }
  }

  /**
   * One GOP (group of pictures — the keyframe interval) per segment, so every segment starts on a
   * keyframe.
   */
  private void addGopSizeArgs(List<String> cmd, TranscodeJob job) {
    var gopSize =
        (int) Math.ceil(job.request().targetSegmentDuration() * job.request().framerate());
    cmd.addAll(
        List.of(
            "-g:v:0", String.valueOf(gopSize),
            "-keyint_min:v:0", String.valueOf(gopSize)));
  }

  private void addForceKeyframeExprArgs(List<String> cmd, TranscodeJob job) {
    cmd.addAll(
        List.of(
            "-force_key_frames:0",
            "expr:gte(t,n_forced*" + job.request().targetSegmentDuration() + ")"));

    if ("libx264".equals(job.videoEncoder())) {
      cmd.addAll(List.of("-sc_threshold:v:0", "0"));
    }
  }

  private void addFragmentedMp4Output(List<String> cmd) {
    cmd.addAll(
        List.of(
            "-f",
            "mp4",
            "-movflags",
            String.join("+", MP4_MOVFLAGS),
            "-frag_duration",
            String.valueOf(TimeUnit.MICROSECONDS.convert(fragmentationTarget)),
            "pipe:1"));
  }
}
