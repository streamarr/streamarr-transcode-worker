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
 * HEVC NAL units and decoder configuration records, as hexadecimal bytes, for probes whose first
 * packets a test writes.
 */
final class HevcSamples {

  // An hvcC record's 22 header bytes, whose NAL units take 4-byte lengths: Main 10, Main tier,
  // level 4.0.
  static final String RECORD_HEADER =
      "01 02 20 00 00 00 90 00 00 00 00 00 78 f0 00 fc fd f8 f8 00 00 0f";
  // The header and no arrays.
  static final String RECORD = RECORD_HEADER + " 00";
  static final String IDR_SLICE = "26 01 af 06 b8";
  static final String SLICE = "02 01 d0 09 7e";
  static final String AUD = "46 01 50";

  private HevcSamples() {}

  /**
   * The chosen video stream of an HEVC probe whose extradata ffprobe dumps as the bytes, whose
   * three first frames carry no side data, and whose first-packets read prints the samples, then
   * slices up to the three packets that it asks for.
   */
  static ProbeStreamInfo probed(String extradata, String... samples) {
    return probe(
            Answers.builder()
                .ffprobeOutput(output("\"codec_name\":\"hevc\"," + extradata(extradata)))
                .firstFrames(frames("", "", ""))
                .firstPackets(packetsUpTo(3, lengthPrefixed(SLICE), samples))
                .build())
        .getStreams(0);
  }
}
