package com.streamarr.transcode.worker.support;

import build.buf.gen.streamarr.transcode.v1.BitrateScanChunk;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import build.buf.gen.streamarr.transcode.v1.ProbePriority;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeAttemptReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

/**
 * Bitrate scan chunk requests as the server builds them from a file's stored keyframe positions,
 * and the per-interval counts that a worker's measurement parts carry.
 */
public final class BitrateScanChunks {

  private BitrateScanChunks() {}

  /** One keyframe on the contract's zero-based timeline, in the positions' time base. */
  public record Keyframe(long presentationTime, long decodeTime) {}

  /**
   * A file's stored keyframe positions: the chosen stream, its time base and every keyframe, with
   * the streams that a chunk measures: the chosen stream and every audio stream.
   */
  @Builder(toBuilder = true)
  public record Positions(
      int streamIndex,
      long timeBaseNumerator,
      long timeBaseDenominator,
      @Singular List<Keyframe> keyframes,
      @Singular List<Integer> measuredStreamIndexes) {

    /**
     * The positions and streams that a probe reported in its result and keyframe position parts.
     */
    public static Positions of(@NonNull ProbeAttemptReport report) {
      var media = report.result().getMedia();
      var positions = media.getKeyframes().getPositions();
      var measured = new ArrayList<Integer>();
      measured.add(positions.getStreamIndex());
      media.getStreamsList().stream()
          .filter(stream -> "audio".equals(stream.getCodecType()))
          .forEach(stream -> measured.add(stream.getIndex()));
      return new Positions(
          positions.getStreamIndex(),
          positions.getTimeBaseNumerator(),
          positions.getTimeBaseDenominator(),
          keyframes(report.keyframePositionsParts()),
          measured);
    }

    private static List<Keyframe> keyframes(List<ProbeKeyframePositionsPart> parts) {
      var keyframes = new ArrayList<Keyframe>();
      long presentation = 0;
      long decode = 0;
      for (var part : parts) {
        for (var i = 0; i < part.getPresentationTimeDeltasCount(); i++) {
          presentation += part.getPresentationTimeDeltas(i);
          decode += part.getDecodeTimeDeltas(i);
          keyframes.add(new Keyframe(presentation, decode));
        }
      }

      return keyframes;
    }

    /**
     * The chunk that holds the keyframes from {@code first} up to {@code end}, exclusive, as the
     * server sends it: the next chunk's first keyframe ends it, and the last chunk has no end.
     */
    public BitrateScanChunk.Builder chunk(int first, int end) {
      var chunk =
          BitrateScanChunk.newBuilder()
              .setMeasurementVersion(FfprobeExecutor.MEASUREMENT_VERSION)
              .setChosenVideoStreamIndex(streamIndex)
              .addAllMeasuredStreamIndexes(measuredStreamIndexes)
              .setTimeBaseNumerator(Math.toIntExact(timeBaseNumerator))
              .setTimeBaseDenominator(Math.toIntExact(timeBaseDenominator))
              .setFirstChunk(first == 0);
      var previous = new Keyframe(0, 0);
      for (var keyframe : keyframes.subList(first, end)) {
        chunk
            .addPresentationTimeDeltas(keyframe.presentationTime() - previous.presentationTime())
            .addDecodeTimeDeltas(keyframe.decodeTime() - previous.decodeTime());
        previous = keyframe;
      }

      if (end < keyframes.size()) {
        chunk
            .setEndPresentationTime(keyframes.get(end).presentationTime())
            .setEndDecodeTime(keyframes.get(end).decodeTime());
      }

      return chunk;
    }
  }

  /** A background probe request that carries the chunk. */
  public static ProbeRequest request(@NonNull BitrateScanChunk.Builder chunk) {
    return WorkerProbeFixtures.requestBuilder()
        .setPriority(ProbePriority.PROBE_PRIORITY_BACKGROUND)
        .setBitrateScanChunk(chunk)
        .build();
  }

  /** One stream's unsigned bytes and packet counts per keyframe interval. */
  public record IntervalCounts(List<Long> bytes, List<Long> packets) {

    public IntervalCounts {
      bytes = List.copyOf(bytes);
      packets = List.copyOf(packets);
    }

    IntervalCounts followedBy(IntervalCounts next) {
      var joinedBytes = new ArrayList<>(bytes);
      joinedBytes.addAll(next.bytes());
      var joinedPackets = new ArrayList<>(packets);
      joinedPackets.addAll(next.packets());
      return new IntervalCounts(joinedBytes, joinedPackets);
    }
  }

  /** Each stream's counts from the parts, joined in part order and keyed in first-seen order. */
  public static Map<Integer, IntervalCounts> intervalCounts(
      @NonNull List<ProbeStreamMeasurementPart> parts) {
    var counts = new LinkedHashMap<Integer, IntervalCounts>();
    for (var part : parts) {
      var bytes =
          part.getKeyframeIntervalBytesList().stream().map(Integer::toUnsignedLong).toList();
      var packets =
          part.getKeyframeIntervalPacketCountsList().stream().map(Integer::toUnsignedLong).toList();
      counts.merge(
          part.getStreamIndex(), new IntervalCounts(bytes, packets), IntervalCounts::followedBy);
    }

    return counts;
  }

  /** The counts of consecutive chunks, joined per stream in chunk order. */
  public static Map<Integer, IntervalCounts> joined(
      @NonNull List<Map<Integer, IntervalCounts>> chunks) {
    var joined = new LinkedHashMap<Integer, IntervalCounts>();
    chunks.forEach(
        chunk ->
            chunk.forEach(
                (stream, counts) -> joined.merge(stream, counts, IntervalCounts::followedBy)));
    return joined;
  }
}
