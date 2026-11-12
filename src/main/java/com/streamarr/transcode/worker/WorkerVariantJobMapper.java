package com.streamarr.transcode.worker;

import static build.buf.gen.streamarr.transcode.v1.AudioMode.AUDIO_MODE_TRANSCODE;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.AudioChannelLayout;
import build.buf.gen.streamarr.transcode.v1.TranscodeExecution;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.engine.AudioDecision;
import com.streamarr.transcode.engine.AudioMode;
import com.streamarr.transcode.engine.EncoderPreset;
import com.streamarr.transcode.engine.ExactSegmentStarts;
import com.streamarr.transcode.engine.OutputFrameRate;
import com.streamarr.transcode.engine.SourceAudio;
import com.streamarr.transcode.engine.SpeakerLayout;
import com.streamarr.transcode.engine.SubtitleDecision;
import com.streamarr.transcode.engine.SubtitleMode;
import com.streamarr.transcode.engine.TranscodeDecision;
import com.streamarr.transcode.engine.TranscodeMode;
import com.streamarr.transcode.engine.TranscodeRequest;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

// Domain and protobuf contracts intentionally share simple names.
final class WorkerVariantJobMapper {

  // ADR 0050 caps an AAC encode at 5.1.
  private static final int AAC_MAXIMUM_CHANNELS = 6;
  private static final Set<String> LOSSLESS_ENCODERS = Set.of("flac", "alac");
  private static final Set<Integer> OUTPUT_BIT_DEPTHS = Set.of(16, 24);
  private static final BigInteger NANOSECONDS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

  private final WorkerMediaSourceResolver sourceResolver;

  WorkerVariantJobMapper(WorkerMediaSourceResolver sourceResolver) {
    this.sourceResolver = sourceResolver;
  }

  /**
   * @throws InvalidJobSpecificationException when the job names no transcode, audio or subtitle
   *     mode, when its video decision is missing, contradicts the rest of the job, or lacks a value
   *     that the worker forces or checks, or when it holds the last frame or pads its audio without
   *     encoding that stream or without a positive advertised end, or when its segment list, in
   *     exact ticks or else in slots, is not empty and does not hold one start for each media
   *     segment, from 0 and strictly increasing, or when its exact starts come without a positive
   *     time base or a time base without exact starts, or when its advertised end does not lie
   *     after the last listed segment's start, or when it encodes video from a later segment
   *     without a keyframe search time, or carries a keyframe search time on any other attempt
   */
  TranscodeRequest map(VariantJob job) {
    return map(job, Optional.empty());
  }

  /**
   * Maps the job with the facts of the audio stream that it encodes, which a job that names its
   * audio's output layout needs.
   *
   * @throws InvalidJobSpecificationException as {@link #map(VariantJob)} does
   */
  TranscodeRequest map(VariantJob job, Optional<SourceAudio> sourceAudio) {
    var variant = job.getVariant();
    var execution = job.getExecution();
    return TranscodeRequest.builder()
        .sessionId(fromProto(job.getStreamSessionId()))
        .attemptId(fromProto(job.getJobAttemptId()))
        .sourcePath(sourceResolver.resolve(job.getSource()))
        .targetSegmentDuration(execution.getTargetSegmentDurationSeconds())
        .outputFrameRate(outputFrameRate(job))
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
        .segmentStartSlots(segmentStartSlots(execution))
        .exactSegmentStarts(exactSegmentStarts(execution))
        .seekPosition(seekPosition(execution))
        .readFromLastKeyframePresentingAtOrBefore(keyframeSearchTime(job))
        .sourceAudio(sourceAudio)
        .advertisedEnd(advertisedEnd(job))
        .build();
  }

  // A job that carries exact segment starts groups on them alone, and its slot list goes unread.
  private static List<Long> segmentStartSlots(TranscodeExecution execution) {
    if (carriesExactSegmentStarts(execution)) {
      return List.of();
    }

    return segmentStartSlotsInSlots(execution);
  }

  // The contract's slots are uint32, which Java reads as int, so they compare as unsigned values.
  private static List<Long> segmentStartSlotsInSlots(TranscodeExecution execution) {
    var slots = execution.getSegmentStartSlotsList().stream().map(Integer::toUnsignedLong).toList();
    requireSegmentList(slots, execution.getMediaSegmentCount(), "slot");
    return slots;
  }

  private static boolean carriesExactSegmentStarts(TranscodeExecution execution) {
    return execution.getSegmentStartTicksCount() > 0 || execution.hasSegmentStartTimeBase();
  }

  // The contract's ticks are uint64, which Java reads as long; a tick count past a signed 64-bit
  // value reads as negative, and the worker refuses it rather than compare it as unsigned.
  private static Optional<ExactSegmentStarts> exactSegmentStarts(TranscodeExecution execution) {
    if (!carriesExactSegmentStarts(execution)) {
      return Optional.empty();
    }

    var ticks = execution.getSegmentStartTicksList();
    if (ticks.isEmpty() || !execution.hasSegmentStartTimeBase()) {
      throw new InvalidJobSpecificationException(
          "The exact segment starts and their time base come together, got "
              + ticks.size()
              + " starts and "
              + (execution.hasSegmentStartTimeBase() ? "a" : "no")
              + " time base");
    }

    if (ticks.stream().anyMatch(tick -> tick < 0)) {
      throw new InvalidJobSpecificationException(
          "The exact segment starts hold a tick count past a signed 64-bit value");
    }

    requireSegmentList(ticks, execution.getMediaSegmentCount(), "tick");
    var timeBase = execution.getSegmentStartTimeBase();
    if (timeBase.getNumerator() == 0 || timeBase.getDenominator() == 0) {
      throw new InvalidJobSpecificationException(
          "The exact segment starts' time base needs a positive numerator and denominator, got "
              + Integer.toUnsignedString(timeBase.getNumerator())
              + "/"
              + Integer.toUnsignedString(timeBase.getDenominator()));
    }

    return Optional.of(
        new ExactSegmentStarts(
            ticks,
            Integer.toUnsignedLong(timeBase.getNumerator()),
            Integer.toUnsignedLong(timeBase.getDenominator())));
  }

  // A non-empty segment list holds one start for each media segment, from 0 and strictly
  // increasing, in units such as slots.
  private static void requireSegmentList(List<Long> starts, int mediaSegmentCount, String unit) {
    if (starts.isEmpty()) {
      return;
    }

    if (starts.size() != mediaSegmentCount) {
      throw new InvalidJobSpecificationException(
          "The segment list holds "
              + starts.size()
              + " starts for "
              + mediaSegmentCount
              + " media segments");
    }

    if (starts.getFirst() != 0) {
      throw new InvalidJobSpecificationException(
          "The segment list starts at " + unit + " " + starts.getFirst() + " instead of 0");
    }

    for (var index = 1; index < starts.size(); index++) {
      requireLaterStart(starts, index, unit);
    }
  }

  private static void requireLaterStart(List<Long> starts, int index, String unit) {
    if (starts.get(index) <= starts.get(index - 1)) {
      throw new InvalidJobSpecificationException(
          "Segment "
              + index
              + " starts at "
              + unit
              + " "
              + starts.get(index)
              + ", not after segment "
              + (index - 1)
              + "'s "
              + unit
              + " "
              + starts.get(index - 1));
    }
  }

  // The worker reads the advertised end only from a job that holds the last frame or pads audio.
  private static Optional<Duration> advertisedEnd(VariantJob job) {
    var decision = job.getDecision();
    var holds = decision.getVideo().getHoldLastFrameToAdvertisedEnd();
    var pads = decision.getAudio().getPadWithSilenceToAdvertisedEnd();
    if (!holds && !pads) {
      return Optional.empty();
    }

    var execution = job.getExecution();
    if (!execution.hasAdvertisedEnd()) {
      throw new InvalidJobSpecificationException(
          "A job that holds the last frame or pads its audio requires the advertised end");
    }

    var end = execution.getAdvertisedEnd();
    var advertisedEnd = Duration.ofSeconds(end.getSeconds(), end.getNanos());
    if (!advertisedEnd.isPositive()) {
      throw new InvalidJobSpecificationException(
          "The advertised end must be positive, got: " + advertisedEnd);
    }

    requireAfterTheLastListedStart(advertisedEnd, execution);
    return Optional.of(advertisedEnd);
  }

  // The last listed segment's audio ends at the advertised end, which must follow its start. The
  // mapping has checked the list's shape before it reads the advertised end.
  private static void requireAfterTheLastListedStart(
      Duration advertisedEnd, TranscodeExecution execution) {
    var lastStart = lastListedStart(execution);
    if (lastStart.isEmpty() || lastStart.get().endsBefore(advertisedEnd)) {
      return;
    }

    throw new InvalidJobSpecificationException(
        "The advertised end "
            + advertisedEnd
            + " does not lie after the last listed segment's start at "
            + lastStart.get());
  }

  private static Optional<ListedStart> lastListedStart(TranscodeExecution execution) {
    var ticks = execution.getSegmentStartTicksList();
    if (!ticks.isEmpty()) {
      var timeBase = execution.getSegmentStartTimeBase();
      return Optional.of(
          new ListedStart(
              ticks.getLast(),
              Integer.toUnsignedLong(timeBase.getNumerator()),
              Integer.toUnsignedLong(timeBase.getDenominator())));
    }

    var slots = execution.getSegmentStartSlotsList();
    if (slots.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        new ListedStart(
            Integer.toUnsignedLong(slots.getLast()),
            execution.getTargetSegmentDurationSeconds(),
            1));
  }

  // A listed start of ticks that each last numerator / denominator seconds; a slot is one tick of
  // a time base whose ticks each last one period.
  private record ListedStart(long ticks, long numerator, long denominator) {

    // Compares by cross-multiplication in nanoseconds, so that neither time is rounded.
    boolean endsBefore(Duration end) {
      var endNanoseconds =
          BigInteger.valueOf(end.getSeconds())
              .multiply(NANOSECONDS_PER_SECOND)
              .add(BigInteger.valueOf(end.getNano()));
      var start =
          BigInteger.valueOf(ticks)
              .multiply(BigInteger.valueOf(numerator))
              .multiply(NANOSECONDS_PER_SECOND);
      return start.compareTo(endNanoseconds.multiply(BigInteger.valueOf(denominator))) < 0;
    }

    @Override
    public String toString() {
      return ticks + " x " + numerator + "/" + denominator + " s";
    }
  }

  private static Optional<OutputFrameRate> outputFrameRate(VariantJob job) {
    if (!encodesVideo(job)) {
      return Optional.empty();
    }

    var rate = job.getExecution().getOutputFrameRate();
    return Optional.of(
        new OutputFrameRate(
            Integer.toUnsignedLong(rate.getNumerator()),
            Integer.toUnsignedLong(rate.getDenominator())));
  }

  private static Optional<Duration> seekPosition(TranscodeExecution execution) {
    if (!execution.hasSeekPosition()) {
      return Optional.empty();
    }

    var position = execution.getSeekPosition();
    return Optional.of(Duration.ofSeconds(position.getSeconds(), position.getNanos()));
  }

  // ADR 0037 (seek): an attempt that encodes video from a later segment reads from the last
  // keyframe that presents at or before the search time and whose read shows a picture by then. A
  // stored keyframe seek position beside the search time is where the worker checks that read
  // first.
  private static Optional<Duration> keyframeSearchTime(VariantJob job) {
    var execution = job.getExecution();
    var encodesFromALaterSegment = encodesVideo(job) && execution.getStartSequenceNumber() > 0;
    var searches = execution.hasReadFromLastKeyframePresentingAtOrBefore();
    if (encodesFromALaterSegment && !searches) {
      throw new InvalidJobSpecificationException(
          "An attempt that encodes video from a later segment carries a keyframe search time");
    }

    if (!searches) {
      return Optional.empty();
    }

    if (!encodesFromALaterSegment) {
      throw new InvalidJobSpecificationException(
          "Only an attempt that encodes video from a later segment carries a keyframe search time");
    }

    var time = execution.getReadFromLastKeyframePresentingAtOrBefore();
    var searchTime = Duration.ofSeconds(time.getSeconds(), time.getNanos());
    if (searchTime.isNegative()) {
      throw new InvalidJobSpecificationException(
          "The keyframe search time must not be negative, got: " + searchTime);
    }

    return Optional.of(searchTime);
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
  private static AudioDecision audio(build.buf.gen.streamarr.transcode.v1.AudioDecision audio) {
    var mode = audioMode(audio.getMode());
    var pads = audio.getPadWithSilenceToAdvertisedEnd();
    if (pads && mode != AudioMode.TRANSCODE) {
      throw new InvalidJobSpecificationException(
          "Only an audio encode pads with silence, and the job's audio mode is " + mode);
    }

    return AudioDecision.builder()
        .mode(mode)
        .codec(audio.hasCodec() ? audio.getCodec() : null)
        .channels(audio.getChannels())
        .bitrate(audio.getBitrateBitsPerSecond())
        .streamIndex(
            audio.hasStreamIndex() ? OptionalInt.of(audio.getStreamIndex()) : OptionalInt.empty())
        .channelLayout(channelLayout(audio))
        .outputSampleRate(outputSampleRate(audio))
        .outputBitDepth(outputBitDepth(audio))
        .padsWithSilenceToAdvertisedEnd(pads)
        .build();
  }

  // The contract sets an output rate only on an encode that names its layout, and never 0 Hz.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static OptionalInt outputSampleRate(
      build.buf.gen.streamarr.transcode.v1.AudioDecision audio) {
    if (!audio.hasOutputSampleRateHz()) {
      return OptionalInt.empty();
    }

    if (!namesLayout(audio) || audio.getOutputSampleRateHz() == 0) {
      throw new InvalidJobSpecificationException(
          "Only an audio encode that names its layout names a sample rate, and never 0 Hz");
    }

    return OptionalInt.of(audio.getOutputSampleRateHz());
  }

  // The contract sets an output depth of 16 or 24 bits only on a FLAC or ALAC encode that names its
  // layout.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static OptionalInt outputBitDepth(
      build.buf.gen.streamarr.transcode.v1.AudioDecision audio) {
    if (!audio.hasOutputBitDepth()) {
      return OptionalInt.empty();
    }

    if (!namesLayout(audio)
        || !LOSSLESS_ENCODERS.contains(audio.getCodec())
        || !OUTPUT_BIT_DEPTHS.contains(audio.getOutputBitDepth())) {
      throw new InvalidJobSpecificationException(
          "Only a FLAC or ALAC encode that names its layout names a bit depth, of 16 or 24 bits");
    }

    return OptionalInt.of(audio.getOutputBitDepth());
  }

  /**
   * Whether the job carries a keyframe search time, so that the engine searches the source for the
   * keyframe that the attempt reads from before it starts (ADR 0037, seek).
   */
  static boolean searchesForItsKeyframe(VariantJob job) {
    return job.getExecution().hasReadFromLastKeyframePresentingAtOrBefore();
  }

  /** Whether the job encodes its audio in a layout that it names. */
  static boolean namesAudioLayout(VariantJob job) {
    return namesLayout(job.getDecision().getAudio());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static boolean namesLayout(build.buf.gen.streamarr.transcode.v1.AudioDecision audio) {
    return audio.getMode() == AUDIO_MODE_TRANSCODE
        && audio.getChannelLayout() != AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_UNSPECIFIED;
  }

  /** FFmpeg's specifier of the source stream that the job copies or encodes as its audio. */
  static String audioStreamSpecifierOf(VariantJob job) {
    return audio(job.getDecision().getAudio()).sourceStreamSpecifier();
  }

  /**
   * The layout that the job names for its audio.
   *
   * @throws InvalidJobSpecificationException when the contract refuses the job's audio decision
   */
  static Optional<SpeakerLayout> audioLayoutOf(VariantJob job) {
    return audio(job.getDecision().getAudio()).channelLayout();
  }

  // The contract refuses a layout on a job that does not encode audio, a value that it does not
  // define, a channel count other than the layout's, and an AAC layout of more than 6 channels.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<SpeakerLayout> channelLayout(
      build.buf.gen.streamarr.transcode.v1.AudioDecision audio) {
    var value = audio.getChannelLayout();
    if (value == AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_UNSPECIFIED) {
      return Optional.empty();
    }

    if (audio.getMode() != AUDIO_MODE_TRANSCODE) {
      throw new InvalidJobSpecificationException("Only an audio encode names a channel layout");
    }

    var layout = SpeakerLayout.named(ffmpegName(value));
    if (layout.channelCount() != audio.getChannels()) {
      throw new InvalidJobSpecificationException(
          "Channel layout "
              + layout.name()
              + " has "
              + layout.channelCount()
              + " channels, but the job names "
              + audio.getChannels());
    }

    if ("aac".equals(audio.getCodec()) && layout.channelCount() > AAC_MAXIMUM_CHANNELS) {
      throw new InvalidJobSpecificationException(
          "AAC carries at most 6 channels, but the job names " + layout.name());
    }

    return Optional.of(layout);
  }

  private static String ffmpegName(AudioChannelLayout layout) {
    return switch (layout) {
      case AUDIO_CHANNEL_LAYOUT_MONO -> "mono";
      case AUDIO_CHANNEL_LAYOUT_STEREO -> "stereo";
      case AUDIO_CHANNEL_LAYOUT_TWO_POINT_ONE -> "2.1";
      case AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO -> "3.0";
      case AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO_BACK -> "3.0(back)";
      case AUDIO_CHANNEL_LAYOUT_FOUR_POINT_ZERO -> "4.0";
      case AUDIO_CHANNEL_LAYOUT_QUAD -> "quad";
      case AUDIO_CHANNEL_LAYOUT_QUAD_SIDE -> "quad(side)";
      case AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE -> "5.1";
      case AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE_SIDE -> "5.1(side)";
      case AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE -> "7.1";
      case AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO -> "5.0";
      case AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE -> "5.0(side)";
      case AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE -> "6.1";
      case AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE_BACK -> "6.1(back)";
      case AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE_WIDE -> "7.1(wide)";
      case AUDIO_CHANNEL_LAYOUT_UNSPECIFIED, UNRECOGNIZED ->
          throw new InvalidJobSpecificationException("The job names an undefined channel layout");
    };
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

  // Whether the job copies the video, with or without an audio encode; a job that names no mode
  // copies none.
  static boolean copiesVideo(VariantJob job) {
    return modeOf(job.getDecision().getMode()).map(mode -> !mode.encodesVideo()).orElse(false);
  }

  static boolean hasEncoderPreset(VariantJob job) {
    return encoderPresetOf(job.getVariant().getEncoderPreset()).isPresent();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<EncoderPreset> encoderPresetOf(
      build.buf.gen.streamarr.transcode.v1.EncoderPreset preset) {
    return switch (preset) {
      case ENCODER_PRESET_SUPERFAST -> Optional.of(EncoderPreset.SUPERFAST);
      case ENCODER_PRESET_VERYFAST -> Optional.of(EncoderPreset.VERYFAST);
      case ENCODER_PRESET_FASTER -> Optional.of(EncoderPreset.FASTER);
      case ENCODER_PRESET_FAST -> Optional.of(EncoderPreset.FAST);
      case ENCODER_PRESET_MEDIUM -> Optional.of(EncoderPreset.MEDIUM);
      case ENCODER_PRESET_SLOW -> Optional.of(EncoderPreset.SLOW);
      case ENCODER_PRESET_SLOWER -> Optional.of(EncoderPreset.SLOWER);
      case ENCODER_PRESET_VERYSLOW -> Optional.of(EncoderPreset.VERYSLOW);
      case ENCODER_PRESET_ULTRAFAST, ENCODER_PRESET_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
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
  private static AudioMode audioMode(build.buf.gen.streamarr.transcode.v1.AudioMode mode) {
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
