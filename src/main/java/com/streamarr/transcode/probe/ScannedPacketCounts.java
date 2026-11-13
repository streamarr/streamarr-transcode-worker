package com.streamarr.transcode.probe;

import com.streamarr.transcode.probe.ChunkPackets.SixBlockDuration;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.KeyframePacketScan.Packet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * Counts the bytes and packets of each stream that a keyframe packet scan copies, in the keyframe
 * intervals of the chosen video stream (ADR 0054). Output stream 0 is the chosen video stream,
 * whose packets fall in intervals by decode order, the order that FFmpeg prints them in. Every
 * other output stream is an audio stream, whose packets fall in the interval that holds their
 * presentation time. A packet before the first keyframe falls in the first interval, and a packet
 * without a presentation time counts at its decode time. Each stream keeps one count for each
 * interval, so the counts grow with the keyframe count rather than the packet count.
 *
 * <p>FFmpeg prints the packets in decode-time order, and its muxing step gives no packet a decode
 * time after its presentation time, so every later keyframe presents at or after the video's latest
 * decode time. An audio packet that presents before that time is counted at once, and any other
 * waits until the video decodes past it or the scan ends. A keyframe that presents at or before a
 * counted audio packet stops the counts rather than leave that packet in the wrong interval.
 *
 * <p>The counts stop, and give no measurement, at a packet with neither time, at an E-AC-3 packet
 * that does not hold six blocks, at an interval count beyond an unsigned 32-bit value, at a time or
 * a span beyond the long range, and when more audio packets wait than {@link
 * #MAXIMUM_WAITING_AUDIO_PACKETS}. The counts also stop when the intervals of every stream together
 * exceed the {@link KeyframePositionsReport#MAXIMUM_INTERVALS_PER_PROBE} entries that the contract
 * carries.
 */
final class ScannedPacketCounts {

  /** The scan holds at most this many audio packets whose keyframe interval it cannot yet tell. */
  private static final int MAXIMUM_WAITING_AUDIO_PACKETS = 1 << 18;

  private static final long LARGEST_INTERVAL_COUNT = 0xFFFF_FFFFL;
  private static final int FIRST_CAPACITY = 1;

  private final List<CountedStream> streams;
  private int keyframeCount;
  private OptionalLong videoDecodeTime = OptionalLong.empty();
  private int waitingPackets;
  private boolean isStopped;

  /**
   * One stream that the scan measures: its index in the source, its time base and, for E-AC-3, the
   * duration of the six blocks that each of its packets must hold for a copy to keep it.
   */
  record MeasuredStream(
      int streamIndex, @NonNull TimeBase timeBase, @NonNull Optional<SixBlockDuration> sixBlocks) {}

  /**
   * Counts the packets of the streams in the order of FFmpeg's output streams, the chosen video
   * stream first. Counts of no streams measure nothing.
   */
  ScannedPacketCounts(@NonNull List<MeasuredStream> streams) {
    this.streams = streams.stream().map(CountedStream::new).toList();
    this.isStopped = streams.isEmpty();
  }

  /** Counts the next packet that FFmpeg printed. */
  void count(Packet packet) {
    if (isStopped) {
      return;
    }

    try {
      countPacket(packet);
    } catch (ArithmeticException _) {
      isStopped = true;
    }
  }

  private void countPacket(Packet packet) {
    var time = packet.pts().isPresent() ? packet.pts() : packet.dts();
    var stream = streams.get(packet.outputStream());
    var duration = Math.max(0, packet.duration());
    if (time.isEmpty() || !stream.holdsSixBlocks(duration)) {
      isStopped = true;
      return;
    }

    stream.extendSpan(time.getAsLong(), duration);
    if (packet.outputStream() == 0) {
      countVideo(stream, packet, time.getAsLong());
      return;
    }

    stream.addWaiting(time.getAsLong(), packet.size());
    waitingPackets++;
    countWaitingAudio(stream);
    if (waitingPackets > MAXIMUM_WAITING_AUDIO_PACKETS) {
      isStopped = true;
    }
  }

  private void countVideo(CountedStream video, Packet packet, long time) {
    if (packet.isKeyframe()) {
      openInterval(video.timeBase, time);
    }

    if (isStopped) {
      return;
    }

    video.add(Math.max(0, keyframeCount - 1), packet.size());
    if (packet.dts().isPresent()) {
      videoDecodeTime = packet.dts();
    }

    audioStreams().forEach(this::countWaitingAudio);
  }

  private void openInterval(TimeBase videoTimeBase, long keyframeTime) {
    keyframeCount++;
    // Each stream keeps a count for every interval. The report carries no measurement beyond the
    // contract's bound, so the counts stop there instead of growing with every later keyframe.
    if ((long) keyframeCount * streams.size()
        > KeyframePositionsReport.MAXIMUM_INTERVALS_PER_PROBE) {
      isStopped = true;
      return;
    }

    for (var audio : audioStreams()) {
      var start = videoTimeBase.rescaleUp(keyframeTime, audio.timeBase);
      if (audio.latestPlacedTime.isPresent() && audio.latestPlacedTime.getAsLong() >= start) {
        isStopped = true;
        return;
      }

      audio.addIntervalStart(keyframeCount, start);
    }

    streams.forEach(stream -> stream.growTo(keyframeCount));
  }

  private void countWaitingAudio(CountedStream audio) {
    if (videoDecodeTime.isEmpty() || audio.isNotWaiting()) {
      return;
    }

    var videoTimeBase = streams.getFirst().timeBase;
    var before = videoTimeBase.rescaleUp(videoDecodeTime.getAsLong(), audio.timeBase);
    while (!audio.isNotWaiting() && audio.firstWaitingTime() < before) {
      placeFirstWaiting(audio);
    }
  }

  private void placeFirstWaiting(CountedStream audio) {
    audio.placeFirstWaiting(keyframeCount);
    waitingPackets--;
  }

  /**
   * Each stream's measurement once FFmpeg printed every packet and the scan found a keyframe, the
   * chosen video stream first; empty when the counts stopped.
   */
  Optional<List<StreamMeasurement>> measurement() {
    if (isStopped) {
      return Optional.empty();
    }

    try {
      audioStreams().forEach(this::placeEveryWaiting);
    } catch (ArithmeticException _) {
      return Optional.empty();
    }

    var measured = new ArrayList<StreamMeasurement>();
    for (var stream : streams) {
      var measurement = stream.measurement(keyframeCount);
      if (measurement.isEmpty()) {
        return Optional.empty();
      }

      measured.add(measurement.get());
    }

    return Optional.of(measured);
  }

  private void placeEveryWaiting(CountedStream audio) {
    while (!audio.isNotWaiting()) {
      placeFirstWaiting(audio);
    }
  }

  private List<CountedStream> audioStreams() {
    return streams.subList(1, streams.size());
  }

  /** One stream's counts, and the packets of an audio stream that wait for their interval. */
  private static final class CountedStream {
    private final int streamIndex;
    private final TimeBase timeBase;
    private final Optional<SixBlockDuration> sixBlocks;
    private int[] intervalBytes = new int[FIRST_CAPACITY];
    private int[] intervalPackets = new int[FIRST_CAPACITY];
    private long[] intervalStarts = new long[FIRST_CAPACITY];
    private long[] waitingTimes = new long[FIRST_CAPACITY];
    private long[] waitingSizes = new long[FIRST_CAPACITY];
    private int firstWaiting;
    private int waitingCount;
    private OptionalLong latestPlacedTime = OptionalLong.empty();
    private boolean hasPackets;
    private long firstTime;
    private long endTime;

    private CountedStream(MeasuredStream stream) {
      this.streamIndex = stream.streamIndex();
      this.timeBase = stream.timeBase();
      this.sixBlocks = stream.sixBlocks();
    }

    boolean holdsSixBlocks(long duration) {
      return sixBlocks.map(rule -> rule.isMetBy(OptionalLong.of(duration))).orElse(true);
    }

    void extendSpan(long time, long duration) {
      var end = Math.addExact(time, duration);
      if (!hasPackets) {
        firstTime = time;
        endTime = end;
        hasPackets = true;
        return;
      }

      firstTime = Math.min(firstTime, time);
      endTime = Math.max(endTime, end);
    }

    void growTo(int intervals) {
      if (intervals > intervalBytes.length) {
        intervalBytes = Arrays.copyOf(intervalBytes, intervals * 2);
        intervalPackets = Arrays.copyOf(intervalPackets, intervals * 2);
      }
    }

    void addIntervalStart(int intervals, long start) {
      if (intervals > intervalStarts.length) {
        intervalStarts = Arrays.copyOf(intervalStarts, intervals * 2);
      }

      intervalStarts[intervals - 1] = start;
    }

    void add(int interval, long size) {
      intervalBytes[interval] = unsignedSum(intervalBytes[interval], size);
      intervalPackets[interval] = unsignedSum(intervalPackets[interval], 1);
    }

    private static int unsignedSum(int count, long addend) {
      var sum = Integer.toUnsignedLong(count) + addend;
      if (sum > LARGEST_INTERVAL_COUNT) {
        throw new ArithmeticException("An interval count exceeds an unsigned 32-bit value");
      }

      return (int) sum;
    }

    void addWaiting(long time, long size) {
      if (firstWaiting + waitingCount == waitingTimes.length) {
        compactWaiting();
      }

      waitingTimes[firstWaiting + waitingCount] = time;
      waitingSizes[firstWaiting + waitingCount] = size;
      waitingCount++;
    }

    // Moves the waiting packets to the front, with twice the room when they fill half of it.
    private void compactWaiting() {
      var room = waitingTimes.length;
      if (waitingCount * 2 > room) {
        room *= 2;
      }

      var times = new long[room];
      var sizes = new long[room];
      System.arraycopy(waitingTimes, firstWaiting, times, 0, waitingCount);
      System.arraycopy(waitingSizes, firstWaiting, sizes, 0, waitingCount);
      waitingTimes = times;
      waitingSizes = sizes;
      firstWaiting = 0;
    }

    boolean isNotWaiting() {
      return waitingCount == 0;
    }

    long firstWaitingTime() {
      return waitingTimes[firstWaiting];
    }

    void placeFirstWaiting(int intervals) {
      var time = waitingTimes[firstWaiting];
      add(intervalHolding(time, intervals), waitingSizes[firstWaiting]);
      latestPlacedTime = OptionalLong.of(Math.max(latestPlacedTime.orElse(time), time));
      firstWaiting++;
      waitingCount--;
    }

    // The last interval that starts at or before the time, or the first when none does.
    private int intervalHolding(long time, int intervals) {
      var low = 0;
      var high = intervals;
      while (low < high) {
        var middle = (low + high) >>> 1;
        if (intervalStarts[middle] <= time) {
          low = middle + 1;
        } else {
          high = middle;
        }
      }

      return Math.max(0, low - 1);
    }

    Optional<StreamMeasurement> measurement(int intervals) {
      if (!hasPackets) {
        return Optional.empty();
      }

      long duration;
      try {
        duration = Math.subtractExact(endTime, firstTime);
      } catch (ArithmeticException _) {
        return Optional.empty();
      }

      var bytes = Arrays.copyOf(intervalBytes, intervals);
      var packets = Arrays.copyOf(intervalPackets, intervals);
      return Optional.of(
          StreamMeasurement.builder()
              .streamIndex(streamIndex)
              .measuredBytes(unsignedTotal(bytes))
              .packetCount(unsignedTotal(packets))
              .timeBase(timeBase)
              .measuredDuration(duration)
              .intervalBytes(bytes)
              .intervalPacketCounts(packets)
              .build());
    }

    private static long unsignedTotal(int[] counts) {
      return Arrays.stream(counts).mapToLong(Integer::toUnsignedLong).sum();
    }
  }
}
