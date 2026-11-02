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

  private static final String FLUSH_PACKETS = "-flush_packets";

  // A negative map undoes the maps before it that it matches, so a named subtitle, data,
  // attachment or cover art stream maps nothing and the producer refuses the output without a
  // sound track, where FFmpeg would fail writing it. The chosen video stream is never cover art.
  private static final List<String> NON_AUDIO_STREAM_EXCLUSIONS =
      List.of("-map", "-0:d", "-map", "-0:t", "-map", "-0:disp:attached_pic");

  // The copy of the source that the encode of a profile 5 source reads: fragmented MP4, whose
  // muxer writes each packet as soon as it has one and whose movie timescale keeps every timestamp
  // the source's demuxer reads. skip_trailer stops the muxer from keeping an index entry of every
  // fragment for a random-access index that the pipe has no use for.
  // FFmpeg writes each output's metadata to its error output, where the worker reads the
  // framecrc of the chosen source streams, so no output copies the source's metadata or chapters.
  private static final List<String> NO_SOURCE_METADATA =
      List.of("-map_metadata", "-1", "-map_chapters", "-1");
  private static final List<String> SOURCE_COPY_OUTPUT =
      List.of(
          "-f",
          "mp4",
          "-movflags",
          "delay_moov+default_base_moof+frag_keyframe+skip_trailer",
          "-frag_duration",
          "1",
          "-movie_timescale",
          "1000000",
          FLUSH_PACKETS,
          "1",
          "pipe:1");

  static final List<String> MP4_MOVFLAGS =
      List.of("cmaf", "delay_moov", "skip_trailer", "frag_keyframe", "frag_discont");

  // How far past the advertised end the padded audio runs: further than any codec's frame lasts, so
  // that the encoder writes the frame that holds the advertised end whole. The producer drops every
  // padded audio sample that starts at or after the advertised end.
  private static final Duration PADDED_AUDIO_RUN_PAST_ADVERTISED_END = Duration.ofSeconds(1);

  private static final String STEREO = "stereo";

  // The input each audio encoder takes, as FFmpeg 8 negotiates it: the sample formats and rates
  // that the encoder lists, and the channel layout that FFmpeg gives the job's channel count, the
  // encoder's first layout of that count or else FFmpeg's default one. A padded encode converts its
  // audio to that input before apad, in the one conversion FFmpeg would run before the encoder: a
  // resampler that met silence instead of the end of its input would change the last source
  // samples it writes, and a conversion split around apad rounds every sample differently.
  private static final Map<Integer, String> DEFAULT_CHANNEL_LAYOUTS =
      Map.of(1, "mono", 2, STEREO, 6, "5.1", 8, "7.1");

  private static final Map<Integer, String> AC3_CHANNEL_LAYOUTS =
      Map.of(1, "mono", 2, STEREO, 6, "5.1(side)");

  private static final Map<String, AudioEncoderInput> AUDIO_ENCODER_INPUTS =
      Map.of(
          "aac",
          AudioEncoderInput.of(
              "fltp",
              "96000|88200|64000|48000|44100|32000|24000|22050|16000|12000|11025|8000|7350",
              DEFAULT_CHANNEL_LAYOUTS),
          "ac3",
          AudioEncoderInput.of("fltp", "48000|44100|32000", AC3_CHANNEL_LAYOUTS),
          "eac3",
          AudioEncoderInput.of("fltp", "48000|44100|32000", AC3_CHANNEL_LAYOUTS),
          "opus",
          AudioEncoderInput.of("fltp", "48000", Map.of(1, "mono", 2, STEREO)),
          "libopus",
          AudioEncoderInput.of("s16|flt", "48000|24000|16000|12000|8000", DEFAULT_CHANNEL_LAYOUTS),
          "flac",
          new AudioEncoderInput("s16|s32", Optional.empty(), DEFAULT_CHANNEL_LAYOUTS));

  // The longest argument Linux passes to a program: MAX_ARG_STRLEN, 32 pages, holds the argument
  // and its terminating NUL, and a page is at least 4 KiB.
  private static final int LONGEST_ARGUMENT_BYTES = 32 * 4096 - 1;

  public FfmpegPipeline buildPipeline(TranscodeJob job) {
    if (needsRpuMove(job)) {
      return new FfmpegPipeline.RpuMovingPipe(
          buildSourceCopyCommand(job), buildCommand(job, EncoderInput.COPY_OF_THE_SOURCE));
    }

    return new FfmpegPipeline.SingleProcess(buildCommand(job, EncoderInput.SOURCE));
  }

  // Dolby Vision profiles 5 and 10.0 have no compatible base, and of those only profile 5 is
  // HEVC, so only its picture needs the RPU that FFmpeg's HEVC decoder skips when the RPU starts a
  // sample. A job whose mode copies the video decodes nothing, whatever its video decision.
  private static boolean needsRpuMove(TranscodeJob job) {
    var decision = job.request().transcodeDecision();
    if (!decision.transcodeMode().encodesVideo()) {
      return false;
    }

    return profile5HevcSourceOf(decision.videoDecision())
        .filter(present -> mayStartSamplesWithRpus(present.rpuPlacement()))
        .isPresent();
  }

  // The copy stamps a packet without a presentation time with its decode time, so the encode of
  // the copy loses the pictures that B-frames reorder. A source whose packets the probe did not see
  // carry presentation times stays with one FFmpeg. For a raw HEVC stream that is enough: FFmpeg's
  // HEVC parser starts each packet at a unit that begins an access unit, never at an RPU after the
  // first packet, so an RPU after its picture's slices ends the packet where the decoder reads it.
  private static Optional<VideoDynamicRange.DolbyVision.Present> profile5HevcSourceOf(
      VideoDecision video) {
    if (!(video instanceof VideoDecision.Encode encode)
        || !encode.sourcePacketsCarryPresentationTimes()
        || !encode.sourceCodecFamily().equals(Optional.of(CodecFamily.HEVC))
        || encode.sourceDynamicRange().compatibleBase() != VideoDynamicRange.CompatibleBase.NONE
        || !(encode.sourceDynamicRange().dolbyVision()
            instanceof VideoDynamicRange.DolbyVision.Present present)) {
      return Optional.empty();
    }

    return Optional.of(present);
  }

  // Samples that start with RPUs are moved as the shifted layout, which mkvmerge wrote before
  // version 57, whether or not its start was cut away. The probe cannot tell that layout from one
  // whose RPUs start their own pictures' samples, which no known muxer writes, and to each of whose
  // pictures the move gives the next picture's RPU. A probe that could not tell the placement may
  // leave RPUs where they start samples or where the decoder ignores them, so the move covers it
  // too: a source whose every RPU ends its sample passes unchanged.
  private static boolean mayStartSamplesWithRpus(
      VideoDynamicRange.DolbyVisionRpuPlacement placement) {
    return switch (placement) {
      case STARTS_SAMPLE, UNSPECIFIED -> true;
      case AFTER_OWN_PICTURE -> false;
    };
  }

  private List<String> buildCommand(TranscodeJob job, EncoderInput input) {
    var cmd = new ArrayList<String>();
    var decision = job.request().transcodeDecision();
    var mode = decision.transcodeMode();

    addInputAndStreamSelection(cmd, job, input);
    addCommonFlags(cmd, input);
    addCodecArgs(cmd, job, input);

    if (mode.encodesVideo()) {
      addFrameRateArgs(cmd, job.request());
      addKeyframeArgs(cmd, job);
    }

    addFragmentedMp4Output(cmd);
    if (input == EncoderInput.SOURCE) {
      addSourceStreamsOutput(cmd, decision);
    }

    return List.copyOf(cmd);
  }

  // The copy seeks as the single command would and keeps the timestamps that command reads, so
  // the encode of the copy reads them unchanged. The copy encodes audio that the job encodes, as
  // it decodes every source codec, and the seek trims that audio exactly as it trims the single
  // command's. FFmpeg's opus encoder is experimental, so the copy names libopus. The mp4 muxer
  // keeps a Dolby Vision configuration record only when the compliance is unofficial.
  private List<String> buildSourceCopyCommand(TranscodeJob job) {
    var cmd = new ArrayList<String>();
    var audio = job.request().transcodeDecision().audioDecision();
    addInputArgs(cmd, job.request());
    addStreamSelection(cmd, job.request().transcodeDecision());
    addCommonFlags(cmd, EncoderInput.SOURCE);
    cmd.addAll(List.of("-c:v", "copy", "-strict", "unofficial"));
    var copiedAudio = audio;
    if (audio.mode() == AudioMode.TRANSCODE && "opus".equals(audio.codec())) {
      copiedAudio =
          AudioDecision.builder()
              .mode(audio.mode())
              .streamIndex(audio.streamIndex())
              .codec("libopus")
              .channels(audio.channels())
              .bitrate(audio.bitrate())
              .channelLayout(audio.channelLayout())
              .outputSampleRate(audio.outputSampleRate())
              .padsWithSilenceToAdvertisedEnd(audio.padsWithSilenceToAdvertisedEnd())
              .build();
    }

    addAudioArgs(cmd, copiedAudio, job.request());

    cmd.addAll(SOURCE_COPY_OUTPUT);
    addSourceStreamsOutput(cmd, job.request().transcodeDecision());
    addVideoTimingOutput(cmd, job.request().transcodeDecision().videoDecision());
    return List.copyOf(cmd);
  }

  // The FFmpeg that reads the source copies the chosen streams' packets, from the keyframe its
  // seek reads on, to a framecrc on its error output, its second output: the worker reads there
  // where each stream's last source sample ends. FFmpeg writes each record at once, so that no log
  // line splits it.
  private static void addSourceStreamsOutput(List<String> cmd, TranscodeDecision decision) {
    cmd.addAll(List.of("-map", "0:" + decision.videoDecision().streamIndex()));
    var audio = decision.audioDecision();
    addAudioStreamSelection(cmd, audio);
    if (namesAudioStream(audio)) {
      cmd.addAll(List.of("-map", "-0:s"));
      cmd.addAll(NON_AUDIO_STREAM_EXCLUSIONS);
    }

    cmd.addAll(List.of("-c", "copy", "-copypriorss", "1"));
    cmd.addAll(NO_SOURCE_METADATA);
    cmd.addAll(List.of("-f", "framecrc", FLUSH_PACKETS, "1", "pipe:2"));
  }

  // The copy's muxer replaces each video packet's duration with the gap to the next packet's
  // decode time; this output's framecrc keeps the duration the source's demuxer read, which the
  // worker writes back into the copy. The worker waits for each packet's record before it passes
  // the packet's sample on, so every record is flushed as it is written.
  private static void addVideoTimingOutput(List<String> cmd, VideoDecision video) {
    cmd.addAll(List.of("-map", "0:" + video.streamIndex(), "-c:v", "copy"));
    cmd.addAll(NO_SOURCE_METADATA);
    cmd.addAll(List.of("-f", "framecrc", FLUSH_PACKETS, "1"));
  }

  // The copy holds only the chosen video stream and the audio. FFmpeg reads no commands from its
  // standard input while it reads media there.
  private void addInputAndStreamSelection(List<String> cmd, TranscodeJob job, EncoderInput input) {
    var decision = job.request().transcodeDecision();
    if (input == EncoderInput.SOURCE) {
      addInputArgs(cmd, job.request());
      addStreamSelection(cmd, decision);
      return;
    }

    cmd.addAll(
        List.of(
            ffmpegPath,
            "-y",
            "-analyzeduration",
            "1",
            "-fpsprobesize",
            "0",
            "-f",
            "mp4",
            "-i",
            "pipe:0",
            "-map",
            "0:v:0"));
    var audio = decision.audioDecision();
    if (audio.mode() != AudioMode.NONE) {
      cmd.addAll(List.of("-map", audioMap(audio, "a:0")));
    }
  }

  private void addInputArgs(List<String> cmd, TranscodeRequest request) {
    cmd.add(ffmpegPath);
    cmd.add("-y");

    var seekPosition = inputSeekPosition(request);
    if (seekPosition.isPositive()) {
      cmd.addAll(List.of("-ss", seekArgument(seekPosition)));
      addHeldFrameSeekArgs(cmd, request);
    }

    encodeInNamedLayout(request.transcodeDecision().audioDecision(), request.sourceAudio())
        .ifPresent(options -> cmd.addAll(options.inputOptions()));
    cmd.addAll(List.of("-i", request.sourcePath().toString()));
  }

  // An attempt that holds the last frame decodes from the keyframe its seek reads and trims
  // its start after the hold, as the encode of the copy of the source does, so that an attempt
  // that starts after the video's last frame still has that frame to repeat. Its encoded audio
  // is trimmed at the same position.
  private static void addHeldFrameSeekArgs(List<String> cmd, TranscodeRequest request) {
    if (holdsLastFrame(request)) {
      cmd.add("-noaccurate_seek");
    }
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

  // An encoded replacement attempt seeks one period before its first segment's start, and the
  // producer discards that period as preroll: a seek to the start itself can drop the frame an
  // earlier attempt repeated there from a variable-frame-rate source, and lands after the start on
  // an MPEG-TS source. A stream copy without the server's keyframe position seeks to its first
  // segment's start.
  private static long inputSeekSeconds(TranscodeRequest request) {
    var firstSegment = (long) request.startSequenceNumber();
    var firstSegmentStart = request.segmentIntervals().startOf(firstSegment, 1);
    var mode = request.transcodeDecision().transcodeMode();
    if (!mode.encodesVideo() || firstSegment == 0) {
      return firstSegmentStart;
    }

    return firstSegmentStart - request.targetSegmentDuration();
  }

  private void addStreamSelection(List<String> cmd, TranscodeDecision decision) {
    cmd.addAll(List.of("-map", "0:" + decision.videoDecision().streamIndex()));
    var audio = decision.audioDecision();
    addAudioStreamSelection(cmd, audio);
    if (decision.subtitleDecision().mode() == SubtitleMode.EXCLUDE || namesAudioStream(audio)) {
      cmd.addAll(List.of("-map", "-0:s"));
    }

    if (namesAudioStream(audio)) {
      cmd.addAll(NON_AUDIO_STREAM_EXCLUSIONS);
    }
  }

  private static void addAudioStreamSelection(List<String> cmd, AudioDecision audio) {
    if (audio.mode() != AudioMode.NONE) {
      cmd.addAll(List.of("-map", audioMap(audio, audio.sourceStreamSpecifier())));
    }
  }

  private static boolean namesAudioStream(AudioDecision audio) {
    return audio.mode() != AudioMode.NONE && audio.streamIndex().isPresent();
  }

  // A stream that the job names maps nothing when the source has no stream there, so that the
  // producer refuses the output that lacks its sound track as the job's error, where FFmpeg would
  // fail to start. Without a name, FFmpeg fails as before when the source has no audio stream.
  private static String audioMap(AudioDecision audio, String specifier) {
    if (audio.streamIndex().isPresent()) {
      return "0:" + specifier + "?";
    }

    return "0:" + specifier;
  }

  // The copy of the source already starts at zero, so the encode of the copy keeps its timestamps.
  private void addCommonFlags(List<String> cmd, EncoderInput input) {
    cmd.addAll(
        List.of(
            "-map_metadata",
            "-1",
            "-map_chapters",
            "-1",
            "-copyts",
            "-avoid_negative_ts",
            "disabled"));
    if (input == EncoderInput.SOURCE) {
      cmd.add("-start_at_zero");
    }

    cmd.addAll(
        List.of(
            "-max_muxing_queue_size",
            "128",
            "-max_interleave_delta",
            MAX_INTERLEAVE_DELTA_MICROSECONDS));
  }

  private void addCodecArgs(List<String> cmd, TranscodeJob job, EncoderInput input) {
    var decision = job.request().transcodeDecision();

    if (!decision.transcodeMode().encodesVideo()) {
      cmd.addAll(List.of("-c:v", "copy"));
      cmd.addAll(copiedVideoArgs(decision.videoDecision()));
      addAudioArgs(cmd, decision.audioDecision(), job.request());
      return;
    }

    cmd.addAll(List.of("-c:v", job.videoEncoder()));
    addPresetArgs(cmd, job);
    PlayableSampleEntry.ofEncoder(job.videoEncoder())
        .ifPresent(entry -> cmd.addAll(tagArgs(entry)));
    cmd.addAll(forcedCodecProfileArgs(job));
    addScaleAndBitrateArgs(cmd, job, trimStart(job.request(), input));
    if (input == EncoderInput.COPY_OF_THE_SOURCE) {
      addAudioOfTheCopyArgs(cmd, decision.audioDecision());
      return;
    }

    addAudioArgs(cmd, decision.audioDecision(), job.request());
  }

  // The copy carries the job's output audio, which the encode of the copy copies untrimmed.
  private static void addAudioOfTheCopyArgs(List<String> cmd, AudioDecision audio) {
    if (audio.mode() != AudioMode.NONE) {
      cmd.addAll(copiedAudioArgs(audio.codec()));
    }
  }

  // The FFmpeg that decodes the source discards the frames before its seek, and, through the
  // source's own markings, such as an MP4 edit list, those before its start. The stream copy keeps
  // every packet from the keyframe at or before its seek, which the FFmpeg that reads the copy
  // would decode and keep, so the encode of the copy trims its video at the attempt's start,
  // seeking or not: after -start_at_zero, nothing else lies before zero. An attempt that holds the
  // last frame and seeks keeps the frames before its seek too.
  private static Optional<String> trimStart(TranscodeRequest request, EncoderInput input) {
    if (input == EncoderInput.SOURCE
        && !(holdsLastFrame(request) && inputSeekPosition(request).isPositive())) {
      return Optional.empty();
    }

    return Optional.of(seekArgument(inputSeekPosition(request)));
  }

  // A copy of H.264 or HEVC gets the codec family's playable sample entry. The mp4 muxer drops a
  // Dolby Vision configuration record unless the compliance is unofficial, which a copy that
  // strips Dolby Vision therefore keeps; no encode writes one.
  private static List<String> copiedVideoArgs(VideoDecision decision) {
    var args = new ArrayList<String>();
    if (decision instanceof VideoDecision.Copy copy) {
      PlayableSampleEntry.ofCopy(copy)
          .ifPresent(entry -> args.addAll(sampleEntryArgs(copy, entry)));
    }

    if (!decision.lists(BitstreamRewrite.STRIP_DOLBY_VISION)) {
      args.addAll(List.of("-strict", "unofficial"));
    }

    return List.copyOf(args);
  }

  // A copy that keeps every packet's bytes passes the source's samples and decoder configuration
  // record to the mp4 muxer unchanged. Any other copy turns its samples into Annex B first.
  private static List<String> sampleEntryArgs(VideoDecision.Copy copy, PlayableSampleEntry entry) {
    if (copy.keepsPacketBytes()) {
      return tagArgs(entry);
    }

    var args = new ArrayList<>(List.of("-bsf:v", entry.annexBFilter()));
    args.addAll(tagArgs(entry));
    return List.copyOf(args);
  }

  private static List<String> tagArgs(PlayableSampleEntry entry) {
    return List.of("-tag:v", entry.tag());
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

  private void addAudioArgs(List<String> cmd, AudioDecision audio, TranscodeRequest request) {
    if (audio.mode() == AudioMode.NONE) {
      return;
    }

    if (audio.mode() == AudioMode.COPY) {
      cmd.addAll(copiedAudioArgs(audio.codec()));
      return;
    }

    var namedLayout = encodeInNamedLayout(audio, request.sourceAudio());
    if (namedLayout.isPresent()) {
      var options = namedLayout.orElseThrow();
      cmd.addAll(
          timingFilter(audio, request)
              .map(options::withTrailingFilter)
              .orElse(options)
              .outputOptions());
      return;
    }

    audioFilter(audio, request).ifPresent(filter -> cmd.addAll(List.of("-af", filter)));
    cmd.addAll(List.of("-c:a", audio.codec()));
    cmd.addAll(List.of("-ac", String.valueOf(audio.channels())));
    cmd.addAll(List.of("-b:a", audio.bitrate() / 1000 + "k"));
  }

  // A job that names its output layout encodes in it (ADR 0050); any other keeps -ac.
  private static Optional<AudioEncodeOptions> encodeInNamedLayout(
      AudioDecision audio, Optional<SourceAudio> sourceAudio) {
    if (audio.mode() != AudioMode.TRANSCODE || audio.channelLayout().isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(AudioEncodeOptions.of(audio, sourceAudio.orElseThrow()));
  }

  private static Optional<String> audioFilter(AudioDecision audio, TranscodeRequest request) {
    var timing = timingFilter(audio, request);
    if (!audio.padsWithSilenceToAdvertisedEnd()) {
      return timing;
    }

    return timing.map(filter -> encoderInputConversion(audio) + filter);
  }

  // apad appends digital silence after the last decoded sample and changes no sample before it.
  // When the attempt reads no source sample, FFmpeg sets the filters up with the stream's own
  // sample rate and layout, as for a decoded one, and apad's silence carries no timestamps, so
  // asetpts starts it at the attempt's start. atrim drops the silence before the start and ends
  // the padding. An attempt that holds the last frame trims the start of its encoded audio itself,
  // as its seek no longer does. In a named layout's encode these filters follow its own: its
  // resampler and limiter meet the end of the source instead of silence, its dither never reaches
  // the silence, and no conversion replaces its layout with FFmpeg's default for the channel count.
  private static Optional<String> timingFilter(AudioDecision audio, TranscodeRequest request) {
    var start = seekArgument(inputSeekPosition(request));
    if (audio.padsWithSilenceToAdvertisedEnd()) {
      var end =
          seekArgument(
              request.advertisedEnd().orElseThrow().plus(PADDED_AUDIO_RUN_PAST_ADVERTISED_END));
      return Optional.of(
          "apad,asetpts='if(isnan(PTS),"
              + start
              + "/TB+N/SR/TB,PTS)',atrim=start="
              + start
              + ":end="
              + end);
    }

    if (holdsLastFrame(request) && inputSeekPosition(request).isPositive()) {
      return Optional.of("atrim=start=" + start);
    }

    return Optional.empty();
  }

  // Empty for an encoder not listed: FFmpeg then converts the padded audio before the encoder.
  private static String encoderInputConversion(AudioDecision audio) {
    return Optional.ofNullable(AUDIO_ENCODER_INPUTS.get(audio.codec()))
        .map(input -> input.conversionFor(audio.channels()) + ",")
        .orElse("");
  }

  // An encoder that takes any sample rate keeps the source's, and a channel count that the table
  // leaves out keeps FFmpeg's own choice of layout.
  private record AudioEncoderInput(
      String sampleFormats, Optional<String> sampleRates, Map<Integer, String> channelLayouts) {

    static AudioEncoderInput of(
        String sampleFormats, String sampleRates, Map<Integer, String> channelLayouts) {
      return new AudioEncoderInput(sampleFormats, Optional.of(sampleRates), channelLayouts);
    }

    String conversionFor(int channels) {
      return "aformat=sample_fmts="
          + sampleFormats
          + sampleRates.map(rates -> ":sample_rates=" + rates).orElse("")
          + Optional.ofNullable(channelLayouts.get(channels))
              .map(layout -> ":channel_layouts=" + layout)
              .orElse("");
    }
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

  private void addScaleAndBitrateArgs(
      List<String> cmd, TranscodeJob job, Optional<String> trimStart) {
    var request = job.request();
    // Every variant is 8-bit 4:2:0: an encoder otherwise keeps a 10-bit or 4:4:4 source's format,
    // and few hardware decoders accept 10-bit H.264.
    var colorConversion =
        toneMaps(job)
            ? ""
            : ":out_color_matrix=bt709:out_range=tv:out_primaries=bt709:out_transfer=bt709";
    // The deinterlacer runs first, so that no filter mixes the two fields of a frame. The held
    // picture is the last one the filters produce from the source, and no filter sees it again.
    var heldFrame = heldFrameFilter(request, trimStart);
    var videoFilter =
        deinterlaceFilter(job)
            + trimStart
                .filter(_ -> heldFrame.isEmpty())
                .map(start -> "trim=start=" + start + ",")
                .orElse("")
            + "scale=-2:"
            + request.height()
            + colorConversion
            + ","
            + videoFormat(job)
            + heldFrame.map(filter -> "," + filter).orElse("");
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

  // fps puts the pictures on the output's frame grid, which -r then keeps, so that tpad repeats
  // the last picture at the output frame rate. At the end of its input fps passes on the pictures
  // it holds: rounding there drops the last picture when its slot on the grid is where the input
  // ends, as for a picture whose duration the source does not give. fps counts presentation times
  // in frames, so trim
  // ends at the first frame of that grid at or after the advertised end, which a time in seconds
  // would round to the nearest frame; a picture keeps its full duration. Empty unless the attempt
  // holds the last frame.
  private static Optional<String> heldFrameFilter(
      TranscodeRequest request, Optional<String> trimStart) {
    if (!holdsLastFrame(request)) {
      return Optional.empty();
    }

    var frameRate = request.outputFrameRate().orElseThrow();
    return Optional.of(
        "fps="
            + frameRate.ffmpegArgument()
            + ":eof_action=pass,tpad=stop=-1:stop_mode=clone,trim="
            + trimStart.map(start -> "start=" + start + ":").orElse("")
            + "end_pts="
            + frameRate.framesStartingBefore(request.advertisedEnd().orElseThrow()));
  }

  private static boolean holdsLastFrame(TranscodeRequest request) {
    return request.transcodeDecision().videoDecision() instanceof VideoDecision.Encode encode
        && encode.holdsLastFrameToAdvertisedEnd();
  }

  private static String deinterlaceFilter(TranscodeJob job) {
    return switch (job.request().transcodeDecision().videoDecision()) {
      case VideoDecision.Copy _ -> "";
      case VideoDecision.Encode encode ->
          encode.deinterlace().map(deinterlace -> deinterlace.filter() + ",").orElse("");
    };
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
    cmd.addAll(List.of("-r:v:0", request.outputFrameRate().orElseThrow().ffmpegArgument()));
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
    var frameRate = request.outputFrameRate().orElseThrow();
    // One frame past the forced keyframes' gap, so the GOP never fires while forcing works, and a
    // keyframe it places for a dropped forced one still lands inside that interval. Past the end of
    // a list cut short, that GOP alone would drift past a segment interval and skip it. The list
    // from the variant's first segment decides, whatever the attempt's start: libx265 writes the
    // GOP into the initialization segment, which every attempt of a variant shares.
    if (isKeyframeVerified(job.videoEncoder())
        && forcedKeyframes(request, 0).reachesLastBoundary()) {
      return Math.addExact(frameRate.framesCovering(request.targetSegmentDuration()), 1);
    }

    // Rounded down, never up: an encoder that ignores the forced keyframe then still places a
    // keyframe inside every segment interval, often two, and never skips a segment.
    return frameRate.wholeFramesIn(request.targetSegmentDuration());
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
  // every attempt forces the same frame at a segment start it shares with another attempt; an
  // expression would measure from the attempt's first frame instead. An attempt's list starts at
  // its first segment, because FFmpeg consumes at most one time per frame, and ends at the last
  // advertised segment's start or the longest argument, past which the GOP places keyframes. A
  // merged segment's later slots get no forced keyframe; the GOP's keyframes there stay inside it.
  private static ForcedKeyframes forcedKeyframes(TranscodeRequest request, long firstSegment) {
    var intervals = request.segmentIntervals();
    var times = new StringBuilder().append(intervals.startOf(firstSegment, 1));
    for (var segment = firstSegment + 1; segment < request.mediaSegmentCount(); segment++) {
      var next = "," + intervals.startOf(segment, 1);
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

  /** What the encoding FFmpeg reads: the source, or the copy of it that the worker passes on. */
  private enum EncoderInput {
    SOURCE,
    COPY_OF_THE_SOURCE
  }
}
