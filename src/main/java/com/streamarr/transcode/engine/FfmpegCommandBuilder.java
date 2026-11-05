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

  private static final String LIBOPUS = "libopus";

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
          LIBOPUS,
          AudioEncoderInput.of("s16|flt", "48000|24000|16000|12000|8000", DEFAULT_CHANNEL_LAYOUTS),
          "flac",
          new AudioEncoderInput("s16|s32", Optional.empty(), DEFAULT_CHANNEL_LAYOUTS));

  // Each encoder's frame and the lead of its first input sample over its first frame without
  // priming, in samples at its sample rate SR, as the pinned FFmpeg writes them: FFmpeg's AAC
  // encoder primes a whole frame of 1,024 samples, AC-3 and E-AC-3 256 of 1,536, FFmpeg's opus
  // encoder 120 of 960, and libopus 6.5 ms of 20 ms. ALAC and FLAC prime nothing. FFmpeg's FLAC
  // encoder frames the longest FLAC block size that lasts at most 105 ms.
  private static final Map<String, AudioFrames> AUDIO_ENCODER_FRAMES =
      Map.of(
          "aac",
          new AudioFrames("1024", "0"),
          "ac3",
          new AudioFrames("1536", "1280"),
          "eac3",
          new AudioFrames("1536", "1280"),
          "opus",
          new AudioFrames("960", "840"),
          LIBOPUS,
          new AudioFrames("SR/50", "SR*27/2000"),
          "alac",
          new AudioFrames("4096", "0"),
          "flac",
          new AudioFrames(longestBlockWithin105Milliseconds(), "0"));

  // How long after the server's keyframe position an encoded replacement attempt's audio starts at
  // the latest: a margin for a demuxer that delivers its first audio sample after the keyframe it
  // seeks to.
  private static final Duration AUDIO_AFTER_THE_KEYFRAME = Duration.ofSeconds(1);

  private static String longestBlockWithin105Milliseconds() {
    var sizes = List.of(32768, 16384, 8192, 4608, 4096, 2304, 2048, 1152, 1024, 576, 512, 256);
    var blockSize = new StringBuilder();
    for (var size : sizes) {
      blockSize.append("if(gte(trunc(SR*105/1000),").append(size).append("),").append(size);
      blockSize.append(',');
    }

    return blockSize.append("192").append(")".repeat(sizes.size())).toString();
  }

  // The longest argument Linux passes to a program: MAX_ARG_STRLEN, 32 pages, holds the argument
  // and its terminating NUL, and a page is at least 4 KiB.
  private static final int LONGEST_ARGUMENT_BYTES = 32 * 4096 - 1;

  public FfmpegPipeline buildPipeline(TranscodeJob job) {
    if (needsRpuMove(job.request())) {
      return new FfmpegPipeline.RpuMovingPipe(
          buildSourceCopyCommand(job), buildCommand(job, EncoderInput.COPY_OF_THE_SOURCE));
    }

    return new FfmpegPipeline.SingleProcess(buildCommand(job, EncoderInput.SOURCE));
  }

  // Dolby Vision profiles 5 and 10.0 have no compatible base, and of those only profile 5 is
  // HEVC, so only its picture needs the RPU that FFmpeg's HEVC decoder skips when the RPU starts a
  // sample. A job whose mode copies the video decodes nothing, whatever its video decision.
  private static boolean needsRpuMove(TranscodeRequest request) {
    var decision = request.transcodeDecision();
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
    addCommonFlags(cmd);
    addCodecArgs(cmd, job, input);

    if (mode.encodesVideo()) {
      addFrameRateArgs(cmd, job.request());
      addKeyframeArgs(cmd, job);
    }

    addFragmentedMp4Output(cmd);
    addSourceStreamsOutput(cmd, decision, input);
    return List.copyOf(cmd);
  }

  // The copy reads the source's video with the single command's seek and keeps the timestamps that
  // the source's demuxer reads, which the encode of the copy shifts as the single command's
  // -start_at_zero shifts its own. The copy carries no audio: the mp4 muxer writes nothing until
  // every track has a sample, so audio would hold back every picture read before its first sample,
  // and the encode of the copy reads the audio from the source itself. The mp4 muxer keeps a Dolby
  // Vision configuration record only when the compliance is unofficial.
  private List<String> buildSourceCopyCommand(TranscodeJob job) {
    var request = job.request();
    var video = request.transcodeDecision().videoDecision();
    var cmd = new ArrayList<>(List.of(ffmpegPath, "-y"));
    addSeekArgs(cmd, request);
    cmd.addAll(List.of("-i", request.sourcePath().toString(), "-map", "0:" + video.streamIndex()));
    addTimestampFlags(cmd);
    addMuxingFlags(cmd);
    cmd.addAll(List.of("-c:v", "copy", "-strict", "unofficial"));
    cmd.addAll(SOURCE_COPY_OUTPUT);
    addVideoTimingOutput(cmd, video);
    return List.copyOf(cmd);
  }

  // The FFmpeg that encodes copies the chosen source streams' packets, from the keyframe its seek
  // reads on, to a framecrc on its error output, its second output: the worker reads there where
  // each stream's last source sample ends. FFmpeg writes each record at once, so that no log line
  // splits it.
  private static void addSourceStreamsOutput(
      List<String> cmd, TranscodeDecision decision, EncoderInput input) {
    var source = input.sourceInputIndex();
    cmd.addAll(List.of("-map", source + ":" + decision.videoDecision().streamIndex()));
    addAudioStreamSelection(cmd, decision.audioDecision(), source);
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

  // The encode of the copy reads the copy's video on its standard input, where FFmpeg reads no
  // commands while it reads media, and the source's audio from the source, seeked as the single
  // command seeks it. -isync shifts the copy's timestamps by the difference between the two inputs'
  // start times: with -start_at_zero, both then lie on the single command's timeline.
  private void addInputAndStreamSelection(List<String> cmd, TranscodeJob job, EncoderInput input) {
    var request = job.request();
    var decision = request.transcodeDecision();
    cmd.addAll(List.of(ffmpegPath, "-y"));
    if (input == EncoderInput.SOURCE) {
      addSourceInput(cmd, request);
      addStreamSelection(cmd, decision);
      return;
    }

    var source = input.sourceInputIndex();
    cmd.addAll(
        List.of(
            "-analyzeduration",
            "1",
            "-fpsprobesize",
            "0",
            "-isync",
            String.valueOf(source),
            "-f",
            "mp4",
            "-i",
            "pipe:0"));
    addSourceInput(cmd, request);
    cmd.addAll(List.of("-map", "0:v:0"));
    addAudioStreamSelection(cmd, decision.audioDecision(), source);
  }

  // ADR 0043 reads each track of an encoded replacement attempt from at most one segment period
  // before its first segment. A filter cannot trim copied packets, so the copy drops the packets
  // before that point, however far back the keyframe that the seek reads lies: an attempt that
  // encodes video from a later segment starts its copied audio one segment period before that
  // segment. One that holds the last frame encodes its video from its seek instead, and the mp4
  // muxer holds every picture until the audio has a packet, so its audio starts at that seek too.
  // Empty for any other attempt.
  private static Optional<Duration> copiedAudioStart(TranscodeRequest request) {
    if (request.transcodeDecision().audioDecision().mode() != AudioMode.COPY) {
      return Optional.empty();
    }

    if (holdsLastFrame(request)) {
      return encodingStart(request);
    }

    return firstSegmentStartOfAnEncode(request)
        .map(start -> start.minus(Duration.ofSeconds(request.targetSegmentDuration())));
  }

  private void addSourceInput(List<String> cmd, TranscodeRequest request) {
    addSeekArgs(cmd, request);
    encodeInNamedLayout(request.transcodeDecision().audioDecision(), request.sourceAudio())
        .ifPresent(options -> cmd.addAll(options.inputOptions()));
    cmd.addAll(List.of("-i", request.sourcePath().toString()));
  }

  private static void addSeekArgs(List<String> cmd, TranscodeRequest request) {
    var seekPosition = inputSeekPosition(request);
    if (seekPosition.isPositive()) {
      cmd.addAll(List.of("-ss", seekArgument(seekPosition)));
      addDecodeOnlySeekArgs(cmd, request);
    }
  }

  // An encode decodes every picture from the keyframe its seek reads, and its filters trim them at
  // the first segment's start after the frame-rate conversion. FFmpeg's exact seek trims before
  // every filter instead, which drops the picture that the conversion repeats across a gap between
  // pictures up to that start.
  private static void addDecodeOnlySeekArgs(List<String> cmd, TranscodeRequest request) {
    if (encodesVideo(request)) {
      cmd.add("-noaccurate_seek");
    }
  }

  // A job that carries the server's keyframe position seeks to that position. A stream copy's
  // keyframe starts the copy's first segment, and the server sends its decode time. An encode's
  // keyframe is the last one whose presentation time lies at or before the first segment's start
  // minus 0.5 s, so that the encode decodes the picture that fills that start. For an encode, the
  // server sends that keyframe's presentation time from an MP4 or MOV source and its decode time
  // from a Matroska, MPEG-TS or any other source. An MP4 demuxer seeks by presentation time, so a
  // decode time before the keyframe's presentation time reads from the keyframe before it, a whole
  // GOP earlier. An MPEG-TS seek reads from the last packet whose decode time is at or before the
  // target, so a seek to the segment's boundary skips that keyframe when its decode time lies far
  // enough before the boundary. The seek can still read from an earlier keyframe: for a stream
  // with B-frames, FFmpeg moves the target 3/23 s earlier unless the container seeks by
  // presentation time, as Matroska and MPEG-TS do not. The producer discards what a copy reads
  // before its first segment as preroll.
  private static Duration inputSeekPosition(TranscodeRequest request) {
    return request.seekPosition().orElseGet(() -> Duration.ofSeconds(inputSeekSeconds(request)));
  }

  // FFmpeg reads microseconds. Rounding up keeps the target at or after the server's position.
  private static String seekArgument(Duration position) {
    return BigDecimal.valueOf(position.getSeconds())
        .add(BigDecimal.valueOf(position.getNano(), 9))
        .setScale(6, RoundingMode.CEILING)
        .stripTrailingZeros()
        .toPlainString();
  }

  // An encoded replacement attempt without the server's keyframe position reads from one period
  // before its first segment's start. That reaches the picture that fills the start unless a gap
  // between pictures covers the whole period, or unless the demuxer lands after the start, as an
  // MPEG-TS demuxer can, and as an MP4 demuxer does for a target inside a gap whose next keyframe
  // carries a decode time inside it. A stream copy without the server's keyframe position seeks to
  // its first segment's start.
  private static long inputSeekSeconds(TranscodeRequest request) {
    var firstSegment = (long) request.startSequenceNumber();
    var firstSegmentStart = request.segmentIntervals().startOf(firstSegment, 1);
    var mode = request.transcodeDecision().transcodeMode();
    if (!mode.encodesVideo() || firstSegment == 0) {
      return firstSegmentStart;
    }

    return firstSegmentStart - request.targetSegmentDuration();
  }

  // Where an attempt that encodes video from a later segment starts encoding video: its first
  // segment's start, as it decodes from its seek up to there. An attempt that holds the last frame
  // starts at its seek instead, where the demuxer delivers a picture. tpad repeats the last
  // picture only after the demuxer reaches the end of the whole source, so a start after that
  // picture would hold back every encoded frame until the demuxer had read all of the audio. The
  // producer discards what that attempt encodes before its first segment as preroll. Empty for any
  // other attempt.
  private static Optional<Duration> encodingStart(TranscodeRequest request) {
    if (holdsLastFrame(request)) {
      return firstSegmentStartOfAnEncode(request)
          .map(_ -> inputSeekPosition(request))
          .filter(Duration::isPositive);
    }

    return firstSegmentStartOfAnEncode(request);
  }

  private static Optional<Duration> firstSegmentStartOfAnEncode(TranscodeRequest request) {
    if (!encodesVideo(request) || request.startSequenceNumber() == 0) {
      return Optional.empty();
    }

    return Optional.of(
        Duration.ofSeconds(request.segmentIntervals().startOf(request.startSequenceNumber(), 1)));
  }

  private static boolean encodesVideo(TranscodeRequest request) {
    return request.transcodeDecision().transcodeMode().encodesVideo();
  }

  private void addStreamSelection(List<String> cmd, TranscodeDecision decision) {
    cmd.addAll(List.of("-map", "0:" + decision.videoDecision().streamIndex()));
    var audio = decision.audioDecision();
    addAudioStreamSelection(cmd, audio, 0);
    if (decision.subtitleDecision().mode() == SubtitleMode.EXCLUDE && !namesAudioStream(audio)) {
      cmd.addAll(List.of("-map", "-0:s"));
    }
  }

  // A negative map undoes the maps before it that it matches, so a named subtitle, data,
  // attachment or cover art stream maps nothing and the producer refuses the output without a
  // sound track, where FFmpeg would fail writing it. The chosen video stream is never cover art.
  private static void addAudioStreamSelection(List<String> cmd, AudioDecision audio, int input) {
    if (audio.mode() == AudioMode.NONE) {
      return;
    }

    cmd.addAll(List.of("-map", audioMap(audio, input)));
    if (namesAudioStream(audio)) {
      var exclusion = "-" + input + ":";
      cmd.addAll(
          List.of(
              "-map",
              exclusion + "s",
              "-map",
              exclusion + "d",
              "-map",
              exclusion + "t",
              "-map",
              exclusion + "disp:attached_pic"));
    }
  }

  private static boolean namesAudioStream(AudioDecision audio) {
    return audio.mode() != AudioMode.NONE && audio.streamIndex().isPresent();
  }

  // A stream that the job names maps nothing when the source has no stream there, so that the
  // producer refuses the output that lacks its sound track as the job's error, where FFmpeg would
  // fail to start. Without a name, FFmpeg fails as before when the source has no audio stream.
  private static String audioMap(AudioDecision audio, int input) {
    var map = input + ":" + audio.sourceStreamSpecifier();
    if (audio.streamIndex().isPresent()) {
      return map + "?";
    }

    return map;
  }

  private static void addCommonFlags(List<String> cmd) {
    addTimestampFlags(cmd);
    cmd.add("-start_at_zero");
    addMuxingFlags(cmd);
  }

  private static void addTimestampFlags(List<String> cmd) {
    cmd.addAll(
        List.of(
            "-map_metadata",
            "-1",
            "-map_chapters",
            "-1",
            "-copyts",
            "-avoid_negative_ts",
            "disabled"));
  }

  private static void addMuxingFlags(List<String> cmd) {
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
    addScaleAndBitrateArgs(cmd, job, input);
    addAudioArgs(cmd, encodedAudio(decision.audioDecision(), input), job.request());
  }

  // FFmpeg's opus encoder is experimental, so the encode of the copy names libopus.
  private static AudioDecision encodedAudio(AudioDecision audio, EncoderInput input) {
    if (input == EncoderInput.SOURCE
        || audio.mode() != AudioMode.TRANSCODE
        || !"opus".equals(audio.codec())) {
      return audio;
    }

    return AudioDecision.builder()
        .mode(audio.mode())
        .streamIndex(audio.streamIndex())
        .codec(LIBOPUS)
        .channels(audio.channels())
        .bitrate(audio.bitrate())
        .channelLayout(audio.channelLayout())
        .outputSampleRate(audio.outputSampleRate())
        .padsWithSilenceToAdvertisedEnd(audio.padsWithSilenceToAdvertisedEnd())
        .build();
  }

  // The FFmpeg that decodes the source discards, through the source's own markings, such as an
  // MP4 edit list, the frames before zero. The stream copy keeps them, so the encode of the copy
  // of an attempt from zero trims its video at zero: after -start_at_zero, nothing else lies
  // before zero. An attempt that holds the last frame trims after the hold.
  private static boolean trimsTheCopyAtZero(TranscodeRequest request, EncoderInput input) {
    return input == EncoderInput.COPY_OF_THE_SOURCE && encodingStart(request).isEmpty();
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
      cmd.addAll(copiedAudioArgs(audio.codec(), copiedAudioStart(request)));
      return;
    }

    var namedLayout = encodeInNamedLayout(audio, request.sourceAudio());
    if (namedLayout.isPresent()) {
      var options = namedLayout.orElseThrow();
      cmd.addAll(
          timingFilter(audio, request, framesOf(options))
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

  // The padding and the frame-aligned shift read the sample rate that the encoder takes, so the
  // conversion to the encoder's input runs before them.
  private static Optional<String> audioFilter(AudioDecision audio, TranscodeRequest request) {
    var frames = framesOf(audio.codec());
    var timing = timingFilter(audio, request, frames);
    var shifts =
        firstSegmentStartOfAnEncode(request)
            .flatMap(_ -> frameAlignedShift(frames, request))
            .isPresent();
    if (!audio.padsWithSilenceToAdvertisedEnd() && !shifts) {
      return timing;
    }

    return timing.map(filter -> encoderInputConversion(audio) + filter);
  }

  // apad appends digital silence after the last decoded sample and changes no sample before it.
  // When the attempt reads no source sample, FFmpeg sets the filters up with the stream's own
  // sample rate and layout, as for a decoded one, and apad's silence carries no timestamps, so
  // asetpts starts it at the attempt's start. atrim drops the silence before the start and ends
  // the padding. An attempt that encodes video from a later segment trims its encoded audio
  // itself, as its seek no longer does. The padding ends after the shift back, on the attempt's own
  // times. In a named layout's encode these filters follow its own: its resampler and limiter meet
  // the end of the source instead of silence, its dither never reaches the silence, and no
  // conversion replaces its layout with FFmpeg's default for the channel count.
  private static Optional<String> timingFilter(
      AudioDecision audio, TranscodeRequest request, Optional<AudioFrames> frames) {
    var trimStart = audioTrimStart(frames, request);
    var start = seekArgument(trimStart.orElseGet(() -> inputSeekPosition(request)));
    var shift =
        firstSegmentStartOfAnEncode(request).flatMap(_ -> frameAlignedShift(frames, request));
    var shiftForward = shift.map(samples -> "asetpts='PTS+" + samples + "',").orElse("");
    var shiftBack = shift.map(samples -> ",asetpts='PTS-" + samples + "'").orElse("");
    if (audio.padsWithSilenceToAdvertisedEnd()) {
      var end =
          seekArgument(
              request.advertisedEnd().orElseThrow().plus(PADDED_AUDIO_RUN_PAST_ADVERTISED_END));
      var endTrim = shift.map(_ -> shiftBack + ",atrim=end=" + end).orElse(":end=" + end);
      return Optional.of(
          shiftForward
              + "apad,asetpts='if(isnan(PTS),"
              + start
              + "/TB+N/SR/TB,PTS)',atrim=start="
              + start
              + endTrim);
    }

    return trimStart.map(_ -> shiftForward + "atrim=start=" + start + shiftBack);
  }

  // Where an attempt that encodes video from a later segment trims its encoded audio: at the first
  // segment's start, between the shifts that align the encoder's frames on it, or, for an encoder
  // whose frames the worker does not know, where the audio's reach back from that start ends.
  private static Optional<Duration> audioTrimStart(
      Optional<AudioFrames> frames, TranscodeRequest request) {
    var firstSegmentStart = firstSegmentStartOfAnEncode(request);
    if (frames.isPresent()) {
      return firstSegmentStart;
    }

    return firstSegmentStart.map(start -> start.minus(audioReach(request)));
  }

  // The encoder's first frame without priming starts on the first segment's start when its input
  // starts the frame's lead, plus whole frames, before that start. The trim between the two shifts
  // cuts the input there, at a time that depends on the sample rate the encoder takes, and so
  // follows the conversion to that rate. Each shift is a whole number of ticks, so that the two
  // cancel. The whole frames reach back to where the demuxer has delivered audio: the seek's
  // target, when a seek without the server's keyframe position lands on a keyframe at or before it,
  // or a second after the server's keyframe position, and no more than one segment period. Every
  // frame from the first segment's start on then starts where the attempt that encodes from the
  // start writes one, give or take less than a frame, and with no gap. The expressions are quoted,
  // as a FLAC frame's holds commas. Empty for an encoder whose frames the worker does not know.
  private static Optional<String> frameAlignedShift(
      Optional<AudioFrames> frames, TranscodeRequest request) {
    var seconds = seekArgument(audioReach(request));
    return frames.map(
        encoderFrames ->
            "round(("
                + encoderFrames.lead()
                + "+floor("
                + seconds
                + "*SR/("
                + encoderFrames.samples()
                + "))*("
                + encoderFrames.samples()
                + "))/SR/TB)");
  }

  private static Optional<AudioFrames> framesOf(String encoder) {
    return Optional.ofNullable(AUDIO_ENCODER_FRAMES.get(encoder));
  }

  // A named layout's encode runs libopus for Opus, and FLAC frames of the size its options set.
  private static Optional<AudioFrames> framesOf(AudioEncodeOptions options) {
    var frames = framesOf(options.encoder());
    var frameSize = options.frameSize();
    if (frameSize.isEmpty()) {
      return frames;
    }

    return frames.map(
        encoderFrames ->
            new AudioFrames(String.valueOf(frameSize.getAsInt()), encoderFrames.lead()));
  }

  // How far before the first segment's start an encoded replacement attempt's audio starts: at
  // most one segment period, so that the audio the producer discards before the first picture
  // stays small however far back the server's keyframe lies. An attempt that holds the last frame
  // encodes its video from its seek, and its audio reaches back as far, so that the mp4 muxer holds
  // back no pictures until the audio's first packet.
  private static Duration audioReach(TranscodeRequest request) {
    var firstSegmentStart = firstSegmentStartOfAnEncode(request).orElseThrow();
    var reach = firstSegmentStart.minus(inputSeekPosition(request));
    if (request.seekPosition().isPresent()) {
      reach = reach.minus(AUDIO_AFTER_THE_KEYFRAME);
    }

    if (reach.isNegative()) {
      return Duration.ZERO;
    }

    var period = Duration.ofSeconds(request.targetSegmentDuration());
    if (reach.compareTo(period) > 0 && !holdsLastFrame(request)) {
      return period;
    }

    return reach;
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

  // FFmpeg expressions of the sample rate SR.
  private record AudioFrames(String samples, String lead) {}

  private static List<String> copiedAudioArgs(String codec, Optional<Duration> start) {
    var args = new ArrayList<>(List.of("-c:a", "copy"));
    var filters = new ArrayList<String>();
    // The noise filter drops each packet whose expression is not zero. It sees the packets on the
    // attempt's timeline, after -start_at_zero, where -copypriorss would compare them with the seek
    // less the source's start time. Register 0 counts the keyframe packets at or after the start,
    // so the copy starts at a keyframe packet, as FFmpeg's own copy does: a TrueHD copy builds its
    // dmlp box from its first packet, which must be a major sync. Every packet of the other copied
    // codecs is a keyframe. The filter runs first, so that the AAC filter takes its configuration
    // from the first packet kept.
    start.ifPresent(
        position ->
            filters.add(
                "noise=drop='eq(st(0,ld(0)+gte(pts*tb," + seekArgument(position) + ")*key),0)'"));
    // delay_moov stops the mp4 muxer from inserting this filter itself, and a copy of the ADTS
    // AAC that MPEG-TS sources carry fails without it. Raw AAC passes through unchanged.
    if ("aac".equals(codec)) {
      filters.add("aac_adtstoasc");
    }

    if (!filters.isEmpty()) {
      args.addAll(List.of("-bsf:a", String.join(",", filters)));
    }

    // The mp4 muxer writes a copied stream's bitrate into the initialization segment's esds and
    // btrt boxes: whatever the demuxer has measured when the output starts. Seeking an MPEG-TS
    // source runs FFmpeg's audio parser over the frames the seek reads, so that average differs
    // with the attempt's start. Zero declares the bitrate unknown, so that every job attempt of a
    // variant writes the same initialization segment.
    args.addAll(List.of("-b:a", "0"));
    return List.copyOf(args);
  }

  private void addScaleAndBitrateArgs(List<String> cmd, TranscodeJob job, EncoderInput input) {
    var request = job.request();
    // Every variant is 8-bit 4:2:0: an encoder otherwise keeps a 10-bit or 4:4:4 source's format,
    // and few hardware decoders accept 10-bit H.264.
    var colorConversion =
        toneMaps(job)
            ? ""
            : ":out_color_matrix=bt709:out_range=tv:out_primaries=bt709:out_transfer=bt709";
    // The deinterlacer runs first, so that no filter mixes the two fields of a frame. The held
    // picture is the last one the filters produce from the source, and no filter sees it again.
    var videoFilter =
        deinterlaceFilter(job)
            + trimBeforeTheScale(request, input)
            + "scale=-2:"
            + request.height()
            + colorConversion
            + ","
            + videoFormat(job)
            + ","
            + frameGridFilter(request)
            + heldFrameFilter(request)
            + trimAfterTheFrameGrid(request, input).map(trim -> ",trim=" + trim).orElse("");
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

  // fps places each picture on the output's frame grid by its presentation time alone, and -r
  // then keeps that grid. FFmpeg's constant-rate conversion behind -r also reads each frame's
  // duration: an MP4 written by a B-frame encoder can give the picture after a gap between pictures
  // the gap's duration, and that conversion then repeated the picture for the gap's length and
  // dropped the source's pictures behind it. At the end of its input fps passes on the picture it
  // holds: rounding there drops the last picture when its slot on the grid is where the input
  // ends, as for a picture whose duration the source does not give.
  private static String frameGridFilter(TranscodeRequest request) {
    return "fps=" + request.outputFrameRate().orElseThrow().ffmpegArgument() + ":eof_action=pass";
  }

  // fps counts presentation times in frames, so the trim starts and ends on that grid: at the
  // first frame at or after the encoding start, as the forced keyframe does at a segment's start,
  // and at the first frame at or after the advertised end, after tpad has repeated the last picture
  // up to it. A time in seconds would round to the nearest frame instead. The trim follows the
  // hold, so that an attempt that starts after the video's last frame still has that frame to
  // repeat.
  private static Optional<String> trimAfterTheFrameGrid(
      TranscodeRequest request, EncoderInput input) {
    var frameRate = request.outputFrameRate().orElseThrow();
    var options = new ArrayList<String>();
    encodingStart(request)
        .ifPresent(start -> options.add("start_pts=" + frameRate.framesStartingBefore(start)));
    if (holdsLastFrame(request) && trimsTheCopyAtZero(request, input)) {
      options.add("start=0");
    }

    if (holdsLastFrame(request)) {
      options.add(
          "end_pts=" + frameRate.framesStartingBefore(request.advertisedEnd().orElseThrow()));
    }

    if (options.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(String.join(":", options));
  }

  private static String trimBeforeTheScale(TranscodeRequest request, EncoderInput input) {
    if (!trimsTheCopyAtZero(request, input) || holdsLastFrame(request)) {
      return "";
    }

    return "trim=start=0,";
  }

  // tpad repeats the last picture at the output frame rate.
  private static String heldFrameFilter(TranscodeRequest request) {
    if (!holdsLastFrame(request)) {
      return "";
    }

    return ",tpad=stop=-1:stop_mode=clone";
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

  // The rate the frame-count GOP is computed from. The fps filter already emits frames at this
  // rate, -r without -fps_mode keeps them, and -copyts keeps a seek's first timestamp.
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

  /**
   * What the encoding FFmpeg reads its video from: the source, or the copy of the source's video
   * that the worker passes on, beside the source, which it then reads as its second input.
   */
  private enum EncoderInput {
    SOURCE(0),
    COPY_OF_THE_SOURCE(1);

    private final int sourceInputIndex;

    EncoderInput(int sourceInputIndex) {
      this.sourceInputIndex = sourceInputIndex;
    }

    int sourceInputIndex() {
      return sourceInputIndex;
    }
  }
}
