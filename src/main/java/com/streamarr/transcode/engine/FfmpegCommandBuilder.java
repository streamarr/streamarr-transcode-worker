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

  // Encoders whose recordings show every time-based forced keyframe as a sync sample that starts a
  // closed GOP, with the GOP count restarting there (src/test/resources/fmp4/README.md, "Verified
  // encoders"; ADR 0037 as amended). libx265 and the hardware encoders are not verified yet.
  private static final Set<String> VERIFIED_ENCODERS = Set.of("libx264", "libsvtav1");

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

  static final List<String> MP4_MOVFLAGS =
      List.of("cmaf", "delay_moov", "skip_trailer", "frag_keyframe", "frag_discont");

  // The longest argument Linux passes to a program: MAX_ARG_STRLEN, 32 pages, holds the argument
  // and its terminating NUL, and a page is at least 4 KiB.
  private static final int LONGEST_ARGUMENT_BYTES = 32 * 4096 - 1;

  public List<String> buildCommand(TranscodeJob job) {
    var cmd = new ArrayList<String>();
    var decision = job.request().transcodeDecision();
    var mode = decision.transcodeMode();

    addInputArgs(cmd, job.request());
    addStreamSelection(cmd, decision.audioDecision(), decision.subtitleDecision());
    addCommonFlags(cmd);
    addCodecArgs(cmd, job);

    if (encodesVideo(mode)) {
      addFrameRateArgs(cmd, job.request());
      addKeyframeArgs(cmd, job);
    }

    addFragmentedMp4Output(cmd);

    return List.copyOf(cmd);
  }

  private static boolean encodesVideo(TranscodeMode mode) {
    return mode == TranscodeMode.VIDEO_TRANSCODE || mode == TranscodeMode.FULL_TRANSCODE;
  }

  private void addInputArgs(List<String> cmd, TranscodeRequest request) {
    cmd.add(ffmpegPath);
    cmd.add("-y");

    var seekSeconds = inputSeekSeconds(request);
    if (seekSeconds > 0) {
      cmd.addAll(List.of("-ss", String.valueOf(seekSeconds)));
    }

    cmd.addAll(List.of("-i", request.sourcePath().toString()));
  }

  // An encoded replacement attempt seeks one period before its first segment, and the producer
  // discards that period as preroll: a seek to the boundary itself can drop the frame an earlier
  // attempt repeated there from a variable-frame-rate source, and lands after the boundary on an
  // MPEG-TS source. A stream copy seeks to its first segment and lands on the keyframe at or
  // before it.
  private static long inputSeekSeconds(TranscodeRequest request) {
    var mode = request.transcodeDecision().transcodeMode();
    if (!encodesVideo(mode) || request.startSequenceNumber() == 0) {
      return request.seekPosition();
    }

    return (request.startSequenceNumber() - 1L) * request.targetSegmentDuration();
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
  private static void addFrameRateArgs(List<String> cmd, TranscodeRequest request) {
    cmd.addAll(List.of("-r:v:0", String.valueOf(request.framerate())));
  }

  private void addKeyframeArgs(List<String> cmd, TranscodeJob job) {
    var gopSize = String.valueOf(gopFrameCount(job));

    cmd.addAll(List.of("-forced-idr", "1"));
    addForcedKeyframeArgs(cmd, job);
    cmd.addAll(List.of("-g:v:0", gopSize));

    if (FIXED_GOP_ENCODERS.contains(job.videoEncoder())) {
      cmd.addAll(List.of("-keyint_min:v:0", gopSize));
    }
  }

  private static int gopFrameCount(TranscodeJob job) {
    var request = job.request();
    var periodFrames = request.targetSegmentDuration() * request.framerate();
    // One frame past the forced keyframes' gap, so the GOP never fires while forcing works, and a
    // keyframe it places for a dropped forced one still lands inside that interval.
    if (VERIFIED_ENCODERS.contains(job.videoEncoder())) {
      return (int) Math.ceil(periodFrames) + 1;
    }

    // Rounded down, never up: an encoder that ignores the forced keyframe then still places a
    // keyframe inside every segment interval, often two, and never skips a segment.
    return (int) Math.floor(periodFrames);
  }

  private void addForcedKeyframeArgs(List<String> cmd, TranscodeJob job) {
    cmd.addAll(List.of("-force_key_frames:0", forcedKeyframeTimes(job.request())));

    if ("libx264".equals(job.videoEncoder())) {
      cmd.addAll(List.of("-sc_threshold:v:0", "0"));
    }
  }

  // FFmpeg's list mode compares each frame's own timestamp with these absolute media times, so
  // every attempt forces the same frame at a boundary it shares with another attempt; an
  // expression would measure from the attempt's first frame instead. The list starts at the
  // attempt's first segment, because FFmpeg consumes at most one time per frame, and ends at the
  // last advertised boundary or the longest argument, past which the GOP places keyframes.
  private static String forcedKeyframeTimes(TranscodeRequest request) {
    var period = (long) request.targetSegmentDuration();
    var times = new StringBuilder().append(request.startSequenceNumber() * period);
    for (var segment = request.startSequenceNumber() + 1L;
        segment < request.mediaSegmentCount();
        segment++) {
      var next = "," + segment * period;
      if (times.length() + next.length() > LONGEST_ARGUMENT_BYTES) {
        break;
      }

      times.append(next);
    }

    return times.toString();
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
