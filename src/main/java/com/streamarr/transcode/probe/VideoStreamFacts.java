package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import tools.jackson.databind.JsonNode;

/** Adds the facts that only a video stream reports to its probe result. */
final class VideoStreamFacts {

  private VideoStreamFacts() {}

  static void add(JsonNode source, ProbeStreamInfo.Builder stream) {
    text(source, "pix_fmt")
        .flatMap(PixelFormat::named)
        .ifPresent(
            format ->
                stream
                    .setBitDepth(format.bitDepth())
                    .setChromaSubsampling(format.chromaSubsampling()));
  }
}
