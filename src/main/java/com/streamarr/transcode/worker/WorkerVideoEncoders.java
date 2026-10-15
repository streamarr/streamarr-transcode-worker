package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.EncoderKind;
import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoEncoderBackend;
import build.buf.gen.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import com.streamarr.transcode.engine.CodecFamily;
import com.streamarr.transcode.engine.EncoderBackend;
import com.streamarr.transcode.engine.WorkerVerifiedEncoder;

// Translates the engine's encoder backends into the contract's.
final class WorkerVideoEncoders {

  private WorkerVideoEncoders() {}

  static WorkerVerifiedVideoEncoder toProto(WorkerVerifiedEncoder encoder) {
    return WorkerVerifiedVideoEncoder.newBuilder()
        .setBackend(toProto(encoder.backend()))
        .setKeyframeVerified(encoder.keyframeVerified())
        .build();
  }

  static VideoEncoderBackend toProto(EncoderBackend backend) {
    return VideoEncoderBackend.newBuilder()
        .setCodecFamily(codecFamily(backend.codecFamily()))
        .setEncoder(backend.encoder())
        .setKind(
            backend.hardware()
                ? EncoderKind.ENCODER_KIND_HARDWARE
                : EncoderKind.ENCODER_KIND_SOFTWARE)
        .build();
  }

  private static VideoCodecFamily codecFamily(CodecFamily family) {
    return switch (family) {
      case H264 -> VideoCodecFamily.VIDEO_CODEC_FAMILY_H264;
      case HEVC -> VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC;
      case AV1 -> VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1;
    };
  }
}
