package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The audio options of a padded encode in a named layout, run by real FFmpeg on a source whose
 * sample rate the encoder cannot take. AC-3 and E-AC-3 code only 32, 44.1 and 48 kHz (ATSC A/52),
 * so the 96 kHz source needs a rate conversion, and without a probed rate the job names none.
 */
@Tag("SmokeTest")
@DisplayName("Named Layout Padding Smoke Tests")
class NamedLayoutPaddingSmokeTest {

  private static final int SOURCE_SAMPLE_RATE = 96_000;
  private static final int SOURCE_SECONDS = 2;
  private static final Duration ADVERTISED_END = Duration.ofSeconds(4);
  // The padded encode runs one second past the advertised end, so the frame that holds it stays
  // whole.
  private static final int PADDED_SECONDS = 5;
  private static final int OUTPUT_SAMPLE_RATE = 48_000;
  // AC-3 and E-AC-3 code 1,536 samples a frame. The frame that holds the source's end, and the
  // block overlap after it, carry the codec's own transition into the silence.
  private static final int FRAME_SAMPLES = 1_536;
  private static final int CHANNELS = 2;

  @TempDir Path root;

  // A resampler after apad would convert with FFmpeg's default settings instead of the chain's
  // own, so the padded encode's frames would differ from the unpadded encode's within the first
  // few, and the silence would pass through its filter.
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"ac3", "eac3"})
  @DisplayName(
      "Should write the unpadded encode's frames, then decode to digital silence up to a second"
          + " past the advertised end, when a padded named-layout encode has no probed sample rate")
  void shouldWriteTheUnpaddedEncodesFramesThenSilenceWhenAPaddedNamedEncodeHasNoProbedRate(
      String codec) throws Exception {
    var source = pinkNoiseThatEndsAtTwoSeconds();

    var unpadded = encode(source, codec, false);
    var padded = encode(source, codec, true);

    var unpaddedFrames = Files.readAllBytes(unpadded);
    var paddedFrames = Files.readAllBytes(padded);
    assertThat(paddedFrames).hasSizeGreaterThan(unpaddedFrames.length);
    assertThat(Arrays.mismatch(Arrays.copyOf(paddedFrames, unpaddedFrames.length), unpaddedFrames))
        .as("the first byte where the padded encode's frames differ from the unpadded encode's")
        .isEqualTo(-1);
    var decoded = decodedSixteenBitSamples(padded);
    var paddedEnd = PADDED_SECONDS * OUTPUT_SAMPLE_RATE;
    assertThat(decoded.length / CHANNELS)
        .as("frames at 48 kHz up to the frame that holds a second past the advertised end")
        .isBetween(paddedEnd, paddedEnd + FRAME_SAMPLES - 1);
    var afterTheLastCodedSourceFrame = SOURCE_SECONDS * OUTPUT_SAMPLE_RATE + FRAME_SAMPLES;
    assertThat(Arrays.copyOfRange(decoded, afterTheLastCodedSourceFrame * CHANNELS, decoded.length))
        .as("16-bit samples after the source's end and its last coded frame")
        .containsOnly((short) 0);
  }

  // Pink noise for two seconds as 96 kHz 16-bit stereo FLAC.
  private Path pinkNoiseThatEndsAtTwoSeconds() throws Exception {
    var source = root.resolve("pink-noise.flac");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "anoisesrc=color=pink:amplitude=0.5:sample_rate="
                + SOURCE_SAMPLE_RATE
                + ":duration="
                + SOURCE_SECONDS
                + ",aformat=sample_fmts=s16:channel_layouts=stereo",
            "-c:a",
            "flac",
            source.toString()));
    return source;
  }

  // The worker's audio options for a stereo encode of the source, as raw frames of the codec.
  private Path encode(Path source, String codec, boolean pads) throws Exception {
    var options = audioOptions(codec, pads);
    var encoded = root.resolve((pads ? "padded." : "unpadded.") + codec);
    var command = new ArrayList<>(List.of("-i", source.toString(), "-map", "0:a"));
    command.addAll(options);
    command.addAll(List.of("-f", codec, encoded.toString()));
    ffmpeg(command);
    return encoded;
  }

  private static List<String> audioOptions(String codec, boolean pads) {
    var audio =
        AudioDecision.builder()
            .mode(AudioMode.TRANSCODE)
            .codec(codec)
            .channels(CHANNELS)
            .bitrate(192_000)
            .channelLayout(Optional.of(SpeakerLayout.named("stereo")))
            .padsWithSilenceToAdvertisedEnd(pads)
            .build();
    var sourceAudio =
        SourceAudio.builder()
            .codec("flac")
            .channels(CHANNELS)
            .channelLayout(Optional.of("stereo"))
            .sampleRate(OptionalInt.empty())
            .build();
    var decision =
        TranscodeDecision.builder()
            .transcodeMode(TranscodeMode.AUDIO_TRANSCODE)
            .videoCodecFamily("h264")
            .videoDecision(VideoDecisionFixtures.forMode(TranscodeMode.AUDIO_TRANSCODE))
            .audioDecision(audio)
            .subtitleDecision(SubtitleDecisions.EXCLUDED)
            .build();
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .targetSegmentDuration(6)
            .mediaSegmentCount(1)
            .transcodeDecision(decision)
            .sourceAudio(Optional.of(sourceAudio))
            .advertisedEnd(Optional.of(ADVERTISED_END))
            .build();
    var job = TranscodeJob.builder().request(request).videoEncoder("copy").build();
    var command =
        ((FfmpegPipeline.SingleProcess)
                new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)).buildPipeline(job))
            .command();
    var options = new ArrayList<String>();
    for (var name : List.of("-c:a", "-af", "-ch_layout", "-b:a")) {
      assertThat(command).containsOnlyOnce(name);
      options.addAll(List.of(name, command.get(command.indexOf(name) + 1)));
    }

    return options;
  }

  // The interleaved samples of every channel, decoded to 16 bits.
  private short[] decodedSixteenBitSamples(Path encoded) throws Exception {
    var decoded = root.resolve(encoded.getFileName() + ".s16le");
    ffmpeg(List.of("-i", encoded.toString(), "-f", "s16le", decoded.toString()));
    var buffer =
        ByteBuffer.wrap(Files.readAllBytes(decoded)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    var samples = new short[buffer.remaining()];
    buffer.get(samples);
    return samples;
  }

  private void ffmpeg(List<String> arguments) throws Exception {
    var command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-loglevel", "error"));
    command.addAll(arguments);
    var errors = root.resolve("ffmpeg-" + System.nanoTime() + ".log");
    var process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(errors.toFile())
            .start();
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("FFmpeg exits").isTrue();
    assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
  }
}
