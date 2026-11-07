package com.streamarr.transcode.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Prints the worker's own FFmpeg command for a start-0 SDR H.264 source encode, one argument per
// line. Args: encoder height bitrate level frameRateNumerator frameRateDenominator segments source
public class PrintWorkerCommand {
  public static void main(String[] a) {
    var encoder = a[0];
    var height = Integer.parseInt(a[1]);
    var bitrate = Long.parseLong(a[2]);
    var level = Integer.parseInt(a[3]);
    var rate = new OutputFrameRate(Integer.parseInt(a[4]), Integer.parseInt(a[5]));
    var segments = Integer.parseInt(a[6]);
    var hevc = encoder.startsWith("hevc") || encoder.equals("libx265");
    var encode =
        VideoDecision.Encode.builder()
            .sourceCodecFamily(Optional.of(CodecFamily.H264))
            .sourceDynamicRange(VideoDynamicRange.sdr())
            .outputDynamicRange(VideoDynamicRange.sdr())
            .codecProfile(hevc ? VideoCodecProfile.HEVC_MAIN : VideoCodecProfile.H264_HIGH)
            .tier(hevc ? Optional.of(VideoCodecTier.MAIN) : Optional.empty())
            .level(level)
            .codecString(hevc ? "hvc1.1.6.L120.90" : "avc1.640028")
            .sourcePacketsCarryPresentationTimes(true)
            .build();
    var decision =
        TranscodeDecision.builder()
            .transcodeMode(TranscodeMode.FULL_TRANSCODE)
            .videoDecision(encode)
            .audioDecision(
                AudioDecision.builder().mode(AudioMode.NONE).codec(null).channels(0).bitrate(0L)
                    .build())
            .subtitleDecision(SubtitleDecisions.EXCLUDED)
            .build();
    var slots = java.util.stream.LongStream.range(0, segments).boxed().toList();
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of(a[7]))
            .startSequenceNumber(0)
            .mediaSegmentCount(segments)
            .segmentStartSlots(slots)
            .targetSegmentDuration(6)
            .outputFrameRate(Optional.of(rate))
            .transcodeDecision(decision)
            .encoderPreset(EncoderPreset.FASTER)
            .height(height)
            .bitrate(bitrate)
            .build();
    var job = TranscodeJob.builder().request(request).videoEncoder(encoder).build();
    var pipeline = new FfmpegCommandBuilder("ffmpeg", Duration.ofMillis(250)).buildPipeline(job);
    List<String> cmd =
        switch (pipeline) {
          case FfmpegPipeline.SingleProcess(var command) -> command;
          default -> throw new IllegalStateException(String.valueOf(pipeline));
        };
    cmd.forEach(System.out::println);
  }
}
