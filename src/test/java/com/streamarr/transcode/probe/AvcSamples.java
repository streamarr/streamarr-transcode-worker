package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packetsUpTo;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;

/**
 * H.264 NAL units and decoder configuration records, as hexadecimal bytes, for probes whose first
 * packets a test writes.
 */
final class AvcSamples {

  // An avcC record whose NAL units take 4-byte lengths, with an SPS and a PPS.
  static final String RECORD = "01 64 00 28 ff e1 00 04 67 64 00 28 01 00 04 68 ee 3c 80";
  static final String IDR_SLICE = "65 88 84 00 18";
  static final String SLICE = "41 9a 24 6c 41";

  private AvcSamples() {}

  /**
   * The chosen video stream of an H.264 probe whose extradata ffprobe dumps as the bytes, whose
   * three first frames carry no side data, and whose first-packets read prints the samples, then
   * slices up to the three packets that it asks for.
   */
  static ProbeStreamInfo probed(String extradata, String... samples) {
    return probe(
            Answers.builder()
                .ffprobeOutput(output("\"codec_name\":\"h264\"," + extradata(extradata)))
                .firstFrames(frames("", "", ""))
                .firstPackets(packetsUpTo(3, lengthPrefixed(SLICE), samples))
                .build())
        .getStreams(0);
  }
}
