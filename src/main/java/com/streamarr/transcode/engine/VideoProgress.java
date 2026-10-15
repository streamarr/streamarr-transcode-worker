package com.streamarr.transcode.engine;

/**
 * Tracks how far a job attempt's video has arrived, by the decode end of its latest video sample.
 * FFmpeg writes no sign that a stream has ended before the whole input ends, so once the audio ends
 * before the video, only the video shows how far the audio has arrived: the muxer interleaves each
 * audio sample with the video around it, unless the input's audio trails its video by more than the
 * muxer's interleave bound or an audio encoder flushes its last frames once the input ends.
 *
 * <p>The muxer interleaves on the input's decode times, while the output's video decode times can
 * run ahead of them: with B-frames the mp4 muxer shifts the video's decode times by the encoder's
 * reordering delay, two frames for libx264, which is two seconds at one frame per second. So the
 * video leads the audio beside it by that delay, and a segment waits for the video to run past its
 * end by the margin plus the most the video has led an audio sample that arrived beside it.
 */
final class VideoProgress {

  // In the recorded outputs an audio sample trails the video written before it by at most 0.162 s,
  // apart from encoded audio that ends long before the video: the muxer stops holding the video for
  // it 10 s after its last packet, the encoder's last frames come out only once the whole input has
  // ended, and the assembler drops the samples that arrive after their segment was delivered.
  private static final long MARGIN_SECONDS = 1;

  private final int periodSeconds;
  // Before any video sample, no segment's end has passed.
  private long endTicks = Long.MIN_VALUE;
  private long timescale = 1;
  // The most the video's decode end has run ahead of an audio sample that arrived after it.
  private long leadTicks;

  VideoProgress(int periodSeconds) {
    this.periodSeconds = periodSeconds;
  }

  /**
   * Widens the video's lead to how far its decode end has run ahead of an audio sample that arrives
   * now, before the video of the sample's fragment.
   *
   * @throws FragmentedMp4Exception when the audio sample's presentation time overflows a signed
   *     64-bit value in the video's timescale
   */
  void addAudioArrival(long presentationTime, long audioTimescale) {
    if (endTicks == Long.MIN_VALUE) {
      return;
    }

    leadTicks = Math.max(leadTicks, endTicks - inVideoTicks(presentationTime, audioTimescale));
  }

  /** Advances to the decode end of the fragment's last video sample, if it carries one. */
  void add(Fragment fragment) {
    if (fragment.videoSamples().isEmpty()) {
      return;
    }

    var last = fragment.videoSamples().getLast();
    endTicks = last.decodeTime() + last.duration();
    timescale = fragment.tracks().video().orElseThrow().timescale();
  }

  /**
   * Whether the video has run a margin and its lead past the end of segment {@code number}, after
   * which the segment closes without waiting for an audio sample of a later one.
   */
  boolean hasPassed(long number) {
    return endTicks >= ((number + 1) * periodSeconds + MARGIN_SECONDS) * timescale + leadTicks;
  }

  private long inVideoTicks(long presentationTime, long audioTimescale) {
    try {
      return Math.floorDiv(Math.multiplyExact(presentationTime, timescale), audioTimescale);
    } catch (ArithmeticException _) {
      throw FragmentedMp4Exception.malformed(
          "audio sample at "
              + presentationTime
              + " overflows a signed 64-bit value in the video's timescale");
    }
  }
}
