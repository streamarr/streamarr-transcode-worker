package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The packets that ffprobe in the pinned worker image listed for a source, recorded by {@code
 * src/test/keyframe-fixtures/record-fixtures.sh}, and what an exact measurement of the source's
 * streams must add up to. The keyframes are the first stream's keyframe packets. A packet of the
 * first stream belongs to the keyframe interval that its listing order places it in, and an audio
 * packet to the interval that holds its presentation time.
 */
record RecordedPackets(Map<Integer, Stream> streams, List<Packet> keyframes) {

  record Packet(int streamIndex, long presentationTime, long duration, long size, String flags) {}

  record Stream(int index, String codecType, long timeBaseDenominator, List<Packet> packets) {

    long bytes() {
      return packets.stream().mapToLong(Packet::size).sum();
    }

    /** The ticks from the first presentation time to the end of the last packet. */
    long duration() {
      var end =
          packets.stream()
              .mapToLong(packet -> packet.presentationTime() + packet.duration())
              .max()
              .orElseThrow();
      return end - firstPresentationTime();
    }

    /** The ticks from the first presentation time to the last packet's presentation time. */
    long spanToLastPacket() {
      var last = packets.stream().mapToLong(Packet::presentationTime).max().orElseThrow();
      return last - firstPresentationTime();
    }

    private long firstPresentationTime() {
      return packets.stream().mapToLong(Packet::presentationTime).min().orElseThrow();
    }
  }

  static RecordedPackets read(Path listing) {
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(Files.readString(listing));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }

    var packetsByStream = new LinkedHashMap<Integer, List<Packet>>();
    for (var packet : root.path("packets")) {
      packetsByStream
          .computeIfAbsent(packet.path("stream_index").asInt(), _ -> new ArrayList<>())
          .add(
              new Packet(
                  packet.path("stream_index").asInt(),
                  packet.path("pts").asLong(),
                  packet.path("duration").asLong(),
                  Long.parseLong(packet.path("size").asString()),
                  packet.path("flags").asString()));
    }

    var streams = new LinkedHashMap<Integer, Stream>();
    for (var stream : root.path("streams")) {
      var index = stream.path("index").asInt();
      var timeBase = stream.path("time_base").asString().split("/");
      assertThat(timeBase[0]).as("time base numerator").isEqualTo("1");
      streams.put(
          index,
          new Stream(
              index,
              stream.path("codec_type").asString(),
              Long.parseLong(timeBase[1]),
              packetsByStream.getOrDefault(index, List.of())));
    }

    var keyframes =
        streams.get(0).packets().stream().filter(packet -> packet.flags().startsWith("K")).toList();
    return new RecordedPackets(streams, keyframes);
  }

  /** The stream's bytes in each keyframe interval of the first stream. */
  long[] intervalBytes(int streamIndex) {
    var stream = streams.get(streamIndex);
    var intervals = new long[keyframes.size()];
    var interval = -1;
    for (var packet : stream.packets()) {
      if (streamIndex == 0) {
        interval += packet.flags().startsWith("K") ? 1 : 0;
        intervals[Math.max(interval, 0)] += packet.size();
        continue;
      }

      intervals[intervalHolding(packet.presentationTime(), stream.timeBaseDenominator())] +=
          packet.size();
    }

    return intervals;
  }

  private int intervalHolding(long presentationTime, long timeBaseDenominator) {
    var video = streams.get(0).timeBaseDenominator();
    var interval = 0;
    for (var i = 0; i < keyframes.size(); i++) {
      var keyframe =
          BigInteger.valueOf(keyframes.get(i).presentationTime())
              .multiply(BigInteger.valueOf(timeBaseDenominator));
      var time = BigInteger.valueOf(presentationTime).multiply(BigInteger.valueOf(video));
      if (time.compareTo(keyframe) >= 0) {
        interval = i;
      }
    }

    return interval;
  }

  /** The last keyframe packet, as ffprobe answers a read of one packet at its time. */
  String lastKeyframePacketJson() {
    var last = keyframes.getLast();
    return """
        {"packets":[{"pts":%d,"flags":"%s"}]}
        """
        .formatted(last.presentationTime(), last.flags());
  }
}
