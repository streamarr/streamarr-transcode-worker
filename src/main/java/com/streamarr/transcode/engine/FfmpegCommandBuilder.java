package com.streamarr.transcode.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class FfmpegCommandBuilder {

  @NonNull private final String ffmpegPath;
  @NonNull private final Duration fragmentationTarget;

  // FFmpeg's default, passed so that a new default cannot move it: the muxer holds one stream's
  // packets until every stream has one or the held span exceeds this, which bounds how far the
  // output's audio trails its video when the input's does not.
  private static final String MAX_INTERLEAVE_DELTA_MICROSECONDS = "10000000";
  // The 8-bit 4:2:0 format every encoder takes unless its traits name another.
  private static final String DEFAULT_EIGHT_BIT_PIXEL_FORMAT = "yuv420p";
  private static final EncoderTraits UNLISTED_ENCODER = traits().build();
  private static final EncoderTraits FIXED_GOP = traits().fixedGop(true).build();
  // QSV and VAAPI take nv12 from system memory and refuse yuv420p.
  private static final String NV12 = "nv12";
  private static final EncoderTraits QSV =
      traits().fixedGop(true).eightBitPixelFormat(NV12).build();
  private static final EncoderTraits VAAPI = traits().eightBitPixelFormat(NV12).build();

  // What the command passes each encoder differently; an encoder not listed has none of it. A
  // keyframe-verified encoder's recordings show every forced keyframe as a sync sample that starts
  // a closed GOP, with its GOP count restarting there (ADR 0037 as amended;
  // src/test/resources/fmp4/README.md, "Verified encoders"); the hardware encoders are not
  // keyframe-verified yet.
  private static final Map<String, EncoderTraits> ENCODER_TRAITS =
      Map.ofEntries(
          Map.entry(
              "libx264",
              traits()
                  .keyframeVerified(true)
                  .takesPresetName(true)
                  .disablesSceneCutsByThreshold(true)
                  .codecProfileForcing(CodecProfileForcing.LIBX264)
                  .build()),
          Map.entry(
              "libsvtav1",
              traits()
                  .keyframeVerified(true)
                  .takesSvtAv1PresetNumber(true)
                  .fixedGop(true)
                  .capsBitrateOnlyInCrfMode(true)
                  .codecProfileForcing(CodecProfileForcing.LIBSVTAV1)
                  .build()),
          Map.entry(
              "libx265",
              traits()
                  .keyframeVerified(true)
                  .takesPresetName(true)
                  .closesGopsByX265Parameters(true)
                  .codecProfileForcing(CodecProfileForcing.LIBX265)
                  .build()),
          Map.entry("h264_nvenc", FIXED_GOP),
          Map.entry("hevc_nvenc", FIXED_GOP),
          Map.entry("av1_nvenc", FIXED_GOP),
          Map.entry("h264_qsv", QSV),
          Map.entry("hevc_qsv", QSV),
          Map.entry("av1_qsv", QSV),
          Map.entry("h264_amf", FIXED_GOP),
          Map.entry("hevc_amf", FIXED_GOP),
          Map.entry("av1_amf", FIXED_GOP),
          Map.entry("h264_rkmpp", FIXED_GOP),
          Map.entry("hevc_rkmpp", FIXED_GOP),
          Map.entry("h264_vaapi", VAAPI),
          Map.entry("hevc_vaapi", VAAPI),
          Map.entry("av1_vaapi", VAAPI));

  // By default x265 opens its GOPs, so a forced keyframe is a CRA picture despite -forced-idr, and
  // it adds a keyframe at each scene cut, inside a segment.
  private static final String CLOSED_GOP_X265_PARAMETERS = "no-scenecut=1:no-open-gop=1";

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

    if (mode.encodesVideo()) {
      addFrameRateArgs(cmd, job.request());
      addKeyframeArgs(cmd, job);
    }

    addFragmentedMp4Output(cmd);

    return List.copyOf(cmd);
  }

  private void addInputArgs(List<String> cmd, TranscodeRequest request) {
    cmd.add(ffmpegPath);
    cmd.add("-y");

    var seekPosition = inputSeekPosition(request);
    if (seekPosition.isPositive()) {
      cmd.addAll(List.of("-ss", seekArgument(seekPosition)));
    }

    cmd.addAll(List.of("-i", request.sourcePath().toString()));
  }

  // A stream copy that carries the server's keyframe position seeks to that position: the decode
  // time of the keyframe that starts the copy's first segment. An MPEG-TS seek reads from the last
  // packet whose decode time is at or before the target, so a seek to the segment's boundary skips
  // that keyframe when its decode time lies far enough before the boundary. The seek can still
  // read from an earlier keyframe: for a stream with B-frames, FFmpeg moves the target 3/23 s
  // earlier unless the container seeks by presentation time, as Matroska and MPEG-TS do not. The
  // producer discards what the attempt reads before its first segment as preroll.
  private static Duration inputSeekPosition(TranscodeRequest request) {
    var copiesVideo = !request.transcodeDecision().transcodeMode().encodesVideo();
    if (copiesVideo && request.seekPosition().isPresent()) {
      return request.seekPosition().orElseThrow();
    }

    return Duration.ofSeconds(inputSeekSeconds(request));
  }

  // FFmpeg reads microseconds. Rounding up keeps the target at or after the server's position.
  private static String seekArgument(Duration position) {
    return BigDecimal.valueOf(position.getSeconds())
        .add(BigDecimal.valueOf(position.getNano(), 9))
        .setScale(6, RoundingMode.CEILING)
        .stripTrailingZeros()
        .toPlainString();
  }

  // An encoded replacement attempt seeks one period before its first segment, and the producer
  // discards that period as preroll: a seek to the boundary itself can drop the frame an earlier
  // attempt repeated there from a variable-frame-rate source, and lands after the boundary on an
  // MPEG-TS source. A stream copy without the server's keyframe position seeks to its first
  // segment's boundary.
  private static long inputSeekSeconds(TranscodeRequest request) {
    var firstSegment = (long) request.startSequenceNumber();
    var mode = request.transcodeDecision().transcodeMode();
    if (!mode.encodesVideo() || firstSegment == 0) {
      return firstSegment * request.targetSegmentDuration();
    }

    return (firstSegment - 1) * request.targetSegmentDuration();
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
            "128",
            "-max_interleave_delta",
            MAX_INTERLEAVE_DELTA_MICROSECONDS));
  }

  private void addCodecArgs(List<String> cmd, TranscodeJob job) {
    var decision = job.request().transcodeDecision();

    if (!decision.transcodeMode().encodesVideo()) {
      cmd.addAll(List.of("-c:v", "copy"));
      cmd.addAll(copiedVideoArgs(decision.videoDecision()));
      addAudioArgs(cmd, decision.audioDecision());
      return;
    }

    cmd.addAll(List.of("-c:v", job.videoEncoder()));
    addPresetArgs(cmd, job);
    PlayableSampleEntry.ofEncoder(job.videoEncoder())
        .ifPresent(entry -> cmd.addAll(List.of("-tag:v", entry.tag())));
    cmd.addAll(forcedCodecProfileArgs(job));
    addScaleAndBitrateArgs(cmd, job);
    addAudioArgs(cmd, decision.audioDecision());
  }

  // A copy of H.264 or HEVC gets the codec family's playable sample entry. The mp4 muxer drops a
  // Dolby Vision configuration record unless the compliance is unofficial; no encode writes one.
  private static List<String> copiedVideoArgs(VideoDecision decision) {
    var args = new ArrayList<String>();
    var sampleEntry =
        switch (decision) {
          case VideoDecision.Copy copy -> PlayableSampleEntry.ofCopy(copy);
          case VideoDecision.Encode _ -> Optional.<PlayableSampleEntry>empty();
        };
    sampleEntry.ifPresent(
        entry -> args.addAll(List.of("-bsf:v", entry.annexBFilter(), "-tag:v", entry.tag())));
    args.addAll(List.of("-strict", "unofficial"));
    return List.copyOf(args);
  }

  // Hardware encoders take no preset until the preset scale has rows for them (ADR 0044).
  private static void addPresetArgs(List<String> cmd, TranscodeJob job) {
    var traits = traitsOf(job);
    if (traits.takesPresetName()) {
      cmd.addAll(List.of("-preset", job.request().encoderPreset().scaleName()));
      return;
    }

    if (traits.takesSvtAv1PresetNumber()) {
      cmd.addAll(List.of("-preset", String.valueOf(job.request().encoderPreset().svtAv1Preset())));
    }
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
    var args = new ArrayList<>(List.of("-c:a", "copy"));
    // delay_moov stops the mp4 muxer from inserting this filter itself, and a copy of the ADTS
    // AAC that MPEG-TS sources carry fails without it. Raw AAC passes through unchanged.
    if ("aac".equals(codec)) {
      args.addAll(List.of("-bsf:a", "aac_adtstoasc"));
    }

    // The mp4 muxer writes a copied stream's bitrate into the initialization segment's esds and
    // btrt boxes: whatever the demuxer has measured when the output starts. Seeking an MPEG-TS
    // source runs FFmpeg's audio parser over the frames the seek reads, so that average differs
    // with the attempt's start. Zero declares the bitrate unknown, so that every job attempt of a
    // variant writes the same initialization segment.
    args.addAll(List.of("-b:a", "0"));
    return List.copyOf(args);
  }

  private void addScaleAndBitrateArgs(List<String> cmd, TranscodeJob job) {
    var request = job.request();
    // Every variant is 8-bit 4:2:0: an encoder otherwise keeps a 10-bit or 4:4:4 source's format,
    // and few hardware decoders accept 10-bit H.264.
    var colorConversion =
        toneMaps(job)
            ? ""
            : ":out_color_matrix=bt709:out_range=tv:out_primaries=bt709:out_transfer=bt709";
    var videoFilter = "scale=-2:" + request.height() + colorConversion + "," + videoFormat(job);
    cmd.addAll(List.of("-vf", videoFilter));
    var bitrate = String.valueOf(request.bitrate());
    // SVT-AV1 supports a bitrate cap only in CRF mode. Its default CRF is 35.
    if (traitsOf(job).capsBitrateOnlyInCrfMode()) {
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

  private static String videoFormat(TranscodeJob job) {
    if (toneMaps(job)) {
      return "tonemapx=tonemap=mobius:peak=100:desat=0:t=bt709:m=bt709:p=bt709:r=tv:format=yuv420p,"
          + "sidedata=mode=delete:type=AMBIENT_VIEWING_ENVIRONMENT";
    }

    return "format=" + traitsOf(job).eightBitPixelFormat();
  }

  private static boolean toneMaps(TranscodeJob job) {
    return job.request().transcodeDecision().videoDecision() instanceof VideoDecision.Encode encode
        && encode.outputDynamicRange().compatibleBase() == VideoDynamicRange.CompatibleBase.SDR
        && switch (encode.sourceDynamicRange().compatibleBase()) {
          case PQ, HLG, NONE -> true;
          case SDR, UNSPECIFIED -> false;
        };
  }

  // The rate the frame-count GOP is computed from; without -fps_mode FFmpeg then emits the
  // constant-rate frames the GOP counts, and -copyts keeps a seek's first timestamp.
  private static void addFrameRateArgs(List<String> cmd, TranscodeRequest request) {
    cmd.addAll(List.of("-r:v:0", String.valueOf(request.framerate())));
  }

  private void addKeyframeArgs(List<String> cmd, TranscodeJob job) {
    var request = job.request();
    var forcedKeyframes = forcedKeyframes(request, request.startSequenceNumber());
    var gopSize = String.valueOf(gopFrameCount(job));

    cmd.addAll(List.of("-forced-idr", "1"));
    addForcedKeyframeArgs(cmd, job, forcedKeyframes);
    cmd.addAll(List.of("-g:v:0", gopSize));

    if (traitsOf(job).fixedGop()) {
      cmd.addAll(List.of("-keyint_min:v:0", gopSize));
    }
  }

  private static int gopFrameCount(TranscodeJob job) {
    var request = job.request();
    var periodFrames = request.targetSegmentDuration() * request.framerate();
    // One frame past the forced keyframes' gap, so the GOP never fires while forcing works, and a
    // keyframe it places for a dropped forced one still lands inside that interval. Past the end of
    // a list cut short, that GOP alone would drift past a segment interval and skip it. The list
    // from the variant's first segment decides, whatever the attempt's start: libx265 writes the
    // GOP into the initialization segment, which every attempt of a variant shares.
    if (isKeyframeVerified(job.videoEncoder())
        && forcedKeyframes(request, 0).reachesLastBoundary()) {
      return (int) Math.ceil(periodFrames) + 1;
    }

    // Rounded down, never up: an encoder that ignores the forced keyframe then still places a
    // keyframe inside every segment interval, often two, and never skips a segment.
    return (int) Math.floor(periodFrames);
  }

  private void addForcedKeyframeArgs(
      List<String> cmd, TranscodeJob job, ForcedKeyframes forcedKeyframes) {
    cmd.addAll(List.of("-force_key_frames:0", forcedKeyframes.times()));

    if (traitsOf(job).disablesSceneCutsByThreshold()) {
      cmd.addAll(List.of("-sc_threshold:v:0", "0"));
    }

    addX265Parameters(cmd, job);
  }

  // libx265 reads its settings from one -x265-params value, and FFmpeg keeps only the last
  // -x265-params of an output, so the closed-GOP settings and the forced level and tier join one
  // value.
  private static void addX265Parameters(List<String> cmd, TranscodeJob job) {
    var parameters = new ArrayList<String>();
    if (traitsOf(job).closesGopsByX265Parameters()) {
      parameters.add(CLOSED_GOP_X265_PARAMETERS);
    }

    parameters.addAll(forcedX265Parameters(job));
    if (parameters.isEmpty()) {
      return;
    }

    cmd.addAll(List.of("-x265-params", String.join(":", parameters)));
  }

  // FFmpeg's list mode compares each frame's own timestamp with these absolute media times, so
  // every attempt forces the same frame at a boundary it shares with another attempt; an
  // expression would measure from the attempt's first frame instead. An attempt's list starts at
  // its first segment, because FFmpeg consumes at most one time per frame, and ends at the last
  // advertised boundary or the longest argument, past which the GOP places keyframes.
  private static ForcedKeyframes forcedKeyframes(TranscodeRequest request, long firstSegment) {
    var period = (long) request.targetSegmentDuration();
    var times = new StringBuilder().append(firstSegment * period);
    for (var segment = firstSegment + 1; segment < request.mediaSegmentCount(); segment++) {
      var next = "," + segment * period;
      if (times.length() + next.length() > LONGEST_ARGUMENT_BYTES) {
        return new ForcedKeyframes(times.toString(), false);
      }

      times.append(next);
    }

    return new ForcedKeyframes(times.toString(), true);
  }

  static boolean isKeyframeVerified(String encoder) {
    return traitsOf(encoder).keyframeVerified();
  }

  private static List<String> forcedCodecProfileArgs(TranscodeJob job) {
    return switch (job.request().transcodeDecision().videoDecision()) {
      case VideoDecision.Copy _ -> List.of();
      case VideoDecision.Encode encode -> traitsOf(job).codecProfileForcing().argumentsFor(encode);
    };
  }

  private static List<String> forcedX265Parameters(TranscodeJob job) {
    return switch (job.request().transcodeDecision().videoDecision()) {
      case VideoDecision.Copy _ -> List.of();
      case VideoDecision.Encode encode ->
          traitsOf(job).codecProfileForcing().x265ParametersFor(encode);
    };
  }

  /** Whether the command forces the job's codec profile, tier and level on the encoder. */
  static boolean forcesCodecProfile(String encoder) {
    return traitsOf(encoder).codecProfileForcing() != CodecProfileForcing.NONE;
  }

  private static EncoderTraits traitsOf(TranscodeJob job) {
    return traitsOf(job.videoEncoder());
  }

  private static EncoderTraits traitsOf(String encoder) {
    return ENCODER_TRAITS.getOrDefault(encoder, UNLISTED_ENCODER);
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

  @Builder
  private record EncoderTraits(
      boolean keyframeVerified,
      boolean takesPresetName,
      boolean takesSvtAv1PresetNumber,
      boolean fixedGop,
      boolean disablesSceneCutsByThreshold,
      boolean closesGopsByX265Parameters,
      boolean capsBitrateOnlyInCrfMode,
      @NonNull String eightBitPixelFormat,
      @NonNull CodecProfileForcing codecProfileForcing) {}

  private static EncoderTraits.EncoderTraitsBuilder traits() {
    return EncoderTraits.builder()
        .eightBitPixelFormat(DEFAULT_EIGHT_BIT_PIXEL_FORMAT)
        .codecProfileForcing(CodecProfileForcing.NONE);
  }

  // How an encoder takes the codec profile, tier and level that the job forces. The hardware
  // encoders force nothing until each passes the forced-value check (worker #14, #56 and #57); the
  // producer's output check fails an attempt whose output differs from the job, so until then the
  // worker runs the software encoder instead.
  private enum CodecProfileForcing {
    NONE,
    LIBX264,
    // libx265 ignores FFmpeg's -level, and writes the High tier unless told otherwise.
    LIBX265,
    // SVT-AV1 writes the Main tier and the only profile that 8-bit 4:2:0 allows.
    LIBSVTAV1;

    List<String> argumentsFor(VideoDecision.Encode encode) {
      var level = String.valueOf(encode.level());
      return switch (this) {
        case NONE -> List.of();
        case LIBX264 ->
            List.of("-profile:v", profileName(encode.codecProfile()), "-level:v", level);
        case LIBX265 -> List.of("-profile:v", profileName(encode.codecProfile()));
        case LIBSVTAV1 -> List.of("-level:v", level);
      };
    }

    List<String> x265ParametersFor(VideoDecision.Encode encode) {
      return switch (this) {
        case LIBX265 -> x265LevelAndTier(encode);
        case NONE, LIBX264, LIBSVTAV1 -> List.of();
      };
    }

    private static String profileName(VideoCodecProfile profile) {
      return switch (profile) {
        case H264_HIGH -> "high";
        case HEVC_MAIN, AV1_MAIN -> "main";
      };
    }

    private static List<String> x265LevelAndTier(VideoDecision.Encode encode) {
      var parameters = new ArrayList<>(List.of("level-idc=" + encode.level()));
      encode
          .tier()
          .ifPresent(tier -> parameters.add("high-tier=" + (tier == VideoCodecTier.HIGH ? 1 : 0)));
      return List.copyOf(parameters);
    }
  }

  private record ForcedKeyframes(@NonNull String times, boolean reachesLastBoundary) {}
}
