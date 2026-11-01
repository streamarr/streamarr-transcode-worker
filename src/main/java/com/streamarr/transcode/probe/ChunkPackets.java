package com.streamarr.transcode.probe;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;
import lombok.Builder;

/**
 * Counts the packets of one bitrate scan chunk into the chunk's keyframe intervals (ADR 0054), from
 * ffprobe's compact packet listing, such as {@code
 * stream_index=0|pts=3500|dts=3458|duration=41|size=1512|flags=K__}. Times are the demuxer's, in
 * each stream's time base. Each stream keeps one count for each interval, so memory grows with the
 * chunk's intervals rather than with its packets.
 *
 * <p>A packet of the chosen video stream counts in decode order: from the packet that presents at
 * the chunk's first keyframe up to the packet that presents at the end keyframe. An audio packet
 * counts in the interval that holds its presentation time. A packet without a presentation time
 * uses its decode time, and a counted packet without either stops the listing. The first chunk also
 * counts the packets before its first keyframe, and the last chunk, which has no end keyframe,
 * counts every packet after its last keyframe.
 *
 * <p>A listing that starts at a seek must reach back to the chunk's start: the first video keyframe
 * must not present after the chunk's first keyframe, and each audio stream's first packet must
 * present before it. Audio packets of one stream follow their presentation order in the file, so
 * every audio packet of the chunk follows that first packet.
 */
final class ChunkPackets implements KeyframePacketScan.PacketLines {

  private static final String STREAM_INDEX = "stream_index";
  private static final String NO_VALUE = "N/A";
  private static final long LARGEST_INTERVAL_COUNT = 0xFFFF_FFFFL;

  private final int chosenStreamIndex;
  private final long[] keyframes;
  private final OptionalLong end;
  private final boolean isFirstChunk;
  private final boolean isReadFromFileStart;
  private final StreamCounts video;
  private final Map<Integer, AudioCounts> audio = new LinkedHashMap<>();

  private int nextKeyframe;
  private int videoInterval;
  private boolean isCountingVideo;
  private boolean isVideoDone;
  private Optional<Stop> stop = Optional.empty();
  private boolean isSeekMissedStart;
  private boolean isStoppedEarly;

  /**
   * {@code keyframes} and {@code end} are the presentation times of the chunk's keyframes and of
   * the next chunk's first keyframe, in the chosen stream's time base. {@code isReadFromFileStart}
   * says that the listing starts at the file's start rather than at a seek.
   */
  @Builder
  private ChunkPackets(
      int chosenStreamIndex,
      long[] keyframes,
      OptionalLong end,
      boolean isFirstChunk,
      boolean isReadFromFileStart,
      List<AudioWindow> audioWindows) {
    this.chosenStreamIndex = chosenStreamIndex;
    this.keyframes = keyframes.clone();
    this.end = end;
    this.isFirstChunk = isFirstChunk;
    this.isReadFromFileStart = isReadFromFileStart;
    this.isCountingVideo = isFirstChunk;
    this.video = new StreamCounts(chosenStreamIndex, keyframes.length);
    for (var window : audioWindows) {
      audio.put(window.streamIndex, new AudioCounts(window, keyframes.length));
    }
  }

  /**
   * One audio stream's interval starts and end in its own time base: the first tick at or after
   * each keyframe's presentation time, and at or after the end keyframe's. {@code sixBlocks} holds
   * the E-AC-3 rule that each counted packet must meet.
   */
  static final class AudioWindow {
    private final int streamIndex;
    private final long[] starts;
    private final OptionalLong end;
    private final Optional<SixBlockDuration> sixBlocks;

    @Builder
    private AudioWindow(
        int streamIndex, long[] starts, OptionalLong end, Optional<SixBlockDuration> sixBlocks) {
      this.streamIndex = streamIndex;
      this.starts = starts.clone();
      this.end = end;
      this.sixBlocks = sixBlocks;
    }
  }

  /**
   * The duration of 1,536 audio samples at the stream's sample rate, in ticks of its time base. A
   * time base coarser than a sample rounds the duration, so a packet holds six blocks when its
   * duration lies within one tick of it.
   */
  record SixBlockDuration(long sampleRate, KeyframeIndex.TimeBase timeBase) {

    private static final long SAMPLES_PER_SIX_BLOCKS = 1_536;

    boolean isMetBy(OptionalLong duration) {
      if (duration.isEmpty()) {
        return false;
      }

      var tick = Math.multiplyExact(sampleRate, timeBase.numerator());
      var difference =
          Math.subtractExact(
              Math.multiplyExact(duration.getAsLong(), tick),
              Math.multiplyExact(SAMPLES_PER_SIX_BLOCKS, timeBase.denominator()));
      return Math.abs(difference) < tick;
    }
  }

  /** Why the listing stopped before the chunk's packets were all read. */
  sealed interface Stop {
    /** A packet gave neither a presentation time nor a decode time. */
    record PacketWithoutTime() implements Stop {}

    /** A stream copy would change the stream's packets. */
    record CopyChangesPackets(int streamIndex) implements Stop {}
  }

  @Override
  public boolean take(String line) {
    var packet = Packet.parse(line);
    if (packet.streamIndex() == chosenStreamIndex) {
      takeVideo(packet);
    }

    var stream = audio.get(packet.streamIndex());
    if (stream != null) {
      takeAudio(stream, packet);
    }

    isStoppedEarly = isSeekMissedStart || stop.isPresent() || isEveryStreamPastTheEnd();
    return !isStoppedEarly;
  }

  private void takeVideo(Packet packet) {
    if (isVideoDone) {
      return;
    }

    var time = packet.time();
    if (time.isPresent() && isEndKeyframe(time.getAsLong())) {
      isVideoDone = true;
      return;
    }

    if (time.isPresent() && isNextKeyframe(time.getAsLong())) {
      videoInterval = nextKeyframe++;
      isCountingVideo = true;
    }

    if (!isCountingVideo) {
      isSeekMissedStart = isPastTheStart(packet);
      return;
    }

    if (time.isEmpty()) {
      stop = Optional.of(new Stop.PacketWithoutTime());
      return;
    }

    video.add(videoInterval, packet);
  }

  private boolean isNextKeyframe(long time) {
    return nextKeyframe < keyframes.length && time == keyframes[nextKeyframe];
  }

  private boolean isEndKeyframe(long time) {
    return nextKeyframe == keyframes.length && end.isPresent() && time == end.getAsLong();
  }

  private boolean isPastTheStart(Packet packet) {
    return !isReadFromFileStart
        && packet.isKeyframe()
        && packet.time().isPresent()
        && packet.time().getAsLong() > keyframes[0];
  }

  private void takeAudio(AudioCounts stream, Packet packet) {
    var time = packet.time();
    if (time.isEmpty()) {
      stop = Optional.of(new Stop.PacketWithoutTime());
      return;
    }

    var window = stream.window();
    var isFirstPacket = !stream.isSeen();
    stream.see();
    if (isFirstPacket && !isReadFromFileStart && time.getAsLong() >= window.starts[0]) {
      isSeekMissedStart = true;
      return;
    }

    if (window.end.isPresent() && time.getAsLong() >= window.end.getAsLong()) {
      stream.passEnd();
      return;
    }

    var interval = stream.intervalOf(time.getAsLong());
    if (interval < 0 && !isFirstChunk) {
      return;
    }

    if (window.sixBlocks.filter(rule -> !rule.isMetBy(packet.duration())).isPresent()) {
      stop = Optional.of(new Stop.CopyChangesPackets(window.streamIndex));
      return;
    }

    stream.counts().add(Math.max(0, interval), packet);
  }

  private boolean isEveryStreamPastTheEnd() {
    return isVideoDone && audio.values().stream().allMatch(AudioCounts::isPastEnd);
  }

  /**
   * Whether these lines asked the runner to stop the listing before its end, so that the listing's
   * exit code tells nothing.
   */
  boolean isStoppedEarly() {
    return isStoppedEarly;
  }

  /** Whether the listing's seek landed after the chunk's start, so it must start earlier. */
  boolean isSeekMissedStart() {
    return isSeekMissedStart;
  }

  /** Why the listing stopped before the chunk's end; empty when it read what the chunk needs. */
  Optional<Stop> stop() {
    return stop;
  }

  /**
   * Whether the listing reached the chunk's end: every keyframe of the chunk, and the end keyframe
   * unless the chunk is the last.
   */
  boolean isComplete() {
    return nextKeyframe == keyframes.length && (end.isEmpty() || isVideoDone);
  }

  /**
   * Each measured stream's counts, the chosen video stream first; empty when an interval holds more
   * bytes or packets than the contract's unsigned 32-bit entries carry.
   */
  Optional<List<StreamCounts>> counts() {
    var counts =
        Stream.concat(Stream.of(video), audio.values().stream().map(AudioCounts::counts)).toList();
    if (counts.stream().anyMatch(StreamCounts::exceedsTheContract)) {
      return Optional.empty();
    }

    return Optional.of(counts);
  }

  /** One stream's packets in the chunk. */
  static final class StreamCounts {
    private final int streamIndex;
    private final long[] intervalBytes;
    private final long[] intervalPackets;
    private long measuredBytes;
    private long packetCount;
    private long firstPresentationTime = Long.MAX_VALUE;
    private long endTime = Long.MIN_VALUE;

    private StreamCounts(int streamIndex, int intervals) {
      this.streamIndex = streamIndex;
      this.intervalBytes = new long[intervals];
      this.intervalPackets = new long[intervals];
    }

    private void add(int interval, Packet packet) {
      intervalBytes[interval] += packet.size();
      intervalPackets[interval]++;
      measuredBytes = Math.addExact(measuredBytes, packet.size());
      packetCount++;
      // Every counted packet has a time: the listing stops at one without.
      var time = packet.time().orElseThrow();
      firstPresentationTime = Math.min(firstPresentationTime, time);
      var duration = Math.max(0, packet.duration().orElse(0));
      endTime = Math.max(endTime, Math.addExact(time, duration));
    }

    private boolean exceedsTheContract() {
      return Stream.of(intervalBytes, intervalPackets)
          .flatMapToLong(Arrays::stream)
          .anyMatch(count -> count > LARGEST_INTERVAL_COUNT);
    }

    int streamIndex() {
      return streamIndex;
    }

    long measuredBytes() {
      return measuredBytes;
    }

    long packetCount() {
      return packetCount;
    }

    /** The earliest presentation time of a counted packet; empty when the chunk holds none. */
    OptionalLong firstPresentationTime() {
      return endTime == Long.MIN_VALUE
          ? OptionalLong.empty()
          : OptionalLong.of(firstPresentationTime);
    }

    /** The latest end of a counted packet: its presentation time plus its duration. */
    OptionalLong endTime() {
      return endTime == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(endTime);
    }

    /** The bytes in each interval as unsigned 32-bit values. */
    int[] intervalBytes() {
      return unsigned(intervalBytes);
    }

    /** The packets in each interval as unsigned 32-bit values. */
    int[] intervalPacketCounts() {
      return unsigned(intervalPackets);
    }

    private static int[] unsigned(long[] counts) {
      var values = new int[counts.length];
      for (var i = 0; i < counts.length; i++) {
        values[i] = (int) counts[i];
      }

      return values;
    }
  }

  private static final class AudioCounts {
    private final AudioWindow window;
    private final StreamCounts counts;
    private boolean isSeen;
    private boolean isPastEnd;

    private AudioCounts(AudioWindow window, int intervals) {
      this.window = window;
      this.counts = new StreamCounts(window.streamIndex, intervals);
    }

    AudioWindow window() {
      return window;
    }

    StreamCounts counts() {
      return counts;
    }

    boolean isSeen() {
      return isSeen;
    }

    void see() {
      isSeen = true;
    }

    boolean isPastEnd() {
      return isPastEnd;
    }

    void passEnd() {
      isPastEnd = true;
    }

    /** The last interval that starts at or before the time; -1 before the first. */
    int intervalOf(long time) {
      var starts = window.starts;
      var low = 0;
      var high = starts.length;
      while (low < high) {
        var middle = (low + high) >>> 1;
        if (starts[middle] <= time) {
          low = middle + 1;
        } else {
          high = middle;
        }
      }

      return low - 1;
    }
  }

  /** One line of the listing. */
  private record Packet(
      int streamIndex,
      OptionalLong presentationTime,
      OptionalLong decodeTime,
      OptionalLong duration,
      long size,
      boolean isKeyframe) {

    /** The presentation time, or the decode time when the packet has no presentation time. */
    OptionalLong time() {
      return presentationTime.isPresent() ? presentationTime : decodeTime;
    }

    static Packet parse(String line) {
      var fields = new LinkedHashMap<String, String>();
      for (var field : line.split("\\|")) {
        var separator = field.indexOf('=');
        if (separator > 0) {
          fields.put(field.substring(0, separator), field.substring(separator + 1));
        }
      }

      try {
        return new Packet(
            Integer.parseInt(required(fields, STREAM_INDEX)),
            optional(fields, "pts"),
            optional(fields, "dts"),
            optional(fields, "duration"),
            Long.parseLong(required(fields, "size")),
            required(fields, "flags").startsWith("K"));
      } catch (NumberFormatException _) {
        throw new PacketScanException("ffprobe printed an unreadable packet line");
      }
    }

    private static String required(Map<String, String> fields, String name) {
      var value = fields.get(name);
      if (value == null) {
        throw new PacketScanException("ffprobe printed a packet line without " + name);
      }

      return value;
    }

    private static OptionalLong optional(Map<String, String> fields, String name) {
      var value = fields.getOrDefault(name, NO_VALUE);
      return NO_VALUE.equals(value) ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
    }
  }
}
