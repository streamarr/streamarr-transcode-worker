package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.ContainerCropping;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.Optional;
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
    sideData(source, "Display Matrix")
        .filter(matrix -> matrix.path("rotation").isNumber())
        .map(VideoStreamFacts::rotationDegrees)
        .ifPresent(stream::setRotationDegrees);
    sideData(source, "Frame Cropping")
        .map(VideoStreamFacts::cropping)
        .ifPresent(stream::setContainerCropping);
    stream.setDynamicRange(DynamicRangeReader.read(source));
    HdrStaticMetadata.masteringDisplay(source).ifPresent(stream::setMasteringDisplay);
    HdrStaticMetadata.contentLightLevel(source).ifPresent(stream::setContentLightLevel);
    CodecProfileReader.add(source, stream);
  }

  private static int rotationDegrees(JsonNode matrix) {
    // ffprobe truncates its rotation field; FFmpeg rounds the full matrix angle before rotating.
    return text(matrix, "displaymatrix")
        .flatMap(DisplayMatrixRotation::read)
        .orElseGet(() -> matrix.path("rotation").asInt());
  }

  /** The stream side data of the given type, as ffprobe's -show_streams lists it. */
  static Optional<JsonNode> sideData(JsonNode source, String type) {
    return source
        .path("side_data_list")
        .valueStream()
        .filter(entry -> type.equals(entry.path("side_data_type").asString("")))
        .findFirst();
  }

  private static ContainerCropping cropping(JsonNode crop) {
    return ContainerCropping.newBuilder()
        .setTop(crop.path("crop_top").asInt())
        .setBottom(crop.path("crop_bottom").asInt())
        .setLeft(crop.path("crop_left").asInt())
        .setRight(crop.path("crop_right").asInt())
        .build();
  }
}
