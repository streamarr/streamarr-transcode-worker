package com.streamarr.transcode.engine;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

/**
 * One {@code moof} with the {@code mdat} that follows it, each box's bytes exactly as read, the
 * tracks its movie declares, and the samples of its video and sound tracks in decode order.
 */
@Builder
record Fragment(
    @NonNull List<byte[]> boxes,
    @NonNull MovieTracks tracks,
    @NonNull List<Sample> videoSamples,
    @NonNull List<Sample> audioSamples)
    implements Mp4Unit {

  // What reading, holding and rebuilding one sample takes from the heap, besides its bytes in the
  // boxes: the sample, the run entry parsed for it, the sample a segment carries, and the list
  // slots that reference them. A fragment's boxes can describe a sample in as little as one byte.
  static final long HEAP_BYTES_PER_SAMPLE = 128;

  Fragment {
    boxes = List.copyOf(boxes);
    videoSamples = List.copyOf(videoSamples);
    audioSamples = List.copyOf(audioSamples);
  }

  /** The {@code mdat} box's bytes, where each sample's position counts from. */
  byte[] mediaDataBox() {
    return boxes.getLast();
  }

  long byteLength() {
    return boxes.stream().mapToLong(box -> box.length).sum();
  }

  /** The memory the fragment holds: its boxes' bytes and the heap its samples take. */
  long heldBytes() {
    return byteLength() + heapBytesOf(videoSamples.size() + (long) audioSamples.size());
  }

  /** The heap that this many samples take once the reader builds them. */
  static long heapBytesOf(long sampleCount) {
    return Math.multiplyExact(sampleCount, HEAP_BYTES_PER_SAMPLE);
  }

  /** The start of the fragment's video track; empty when it carries no video sample. */
  Optional<VideoStart> videoStart() {
    return tracks.video().flatMap(track -> track.startOf(videoSamples));
  }

  /** The shortest duration among the fragment's video samples, in the video track's timescale. */
  OptionalLong shortestVideoSampleDuration() {
    return videoSamples.stream().mapToLong(Sample::duration).min();
  }
}
