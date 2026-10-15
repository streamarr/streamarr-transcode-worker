package com.streamarr.transcode.worker.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.Builder;

/** The packets ffprobe reads from a fragmented MP4 output, in the order it reads them. */
public final class FfprobePackets {

  private static final int VIDEO_STREAM = 0;
  private static final int AUDIO_STREAM = 1;

  private FfprobePackets() {}

  /** Runs ffprobe over the output, written to a file in the directory, and returns every packet. */
  public static List<Packet> of(byte[] output, Path directory) throws IOException {
    var file = Files.createTempFile(directory, "probed", ".mp4");
    Files.write(file, output);
    var listing = Files.createTempFile(directory, "packets", ".csv");
    var command = new ArrayList<String>();
    command.add("ffprobe");
    command.addAll(listingArguments());
    command.add(file.toString());
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(listing.toFile())
            .start();
    try {
      assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("ffprobe finished").isTrue();
      assertThat(process.exitValue()).as(Files.readString(listing)).isZero();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while ffprobe read " + file, e);
    } finally {
      process.destroyForcibly();
    }

    return parse(Files.readString(listing));
  }

  /**
   * Every packet of a listing that ffprobe wrote with the entries and data hash that {@link #of}
   * asks for.
   */
  public static List<Packet> parse(String listing) {
    return listing.lines().filter(line -> !line.isBlank()).map(Packet::parse).toList();
  }

  /**
   * The ffprobe arguments that list each packet's entries and data hash as {@link #parse} reads
   * them.
   */
  public static List<String> listingArguments() {
    return List.of(
        "-v",
        "error",
        "-show_entries",
        "packet=stream_index,pts,dts,duration,size,flags,data_hash",
        "-show_data_hash",
        "sha256",
        "-of",
        "csv=p=0");
  }

  /**
   * One packet: its stream (0 video, 1 audio, in the {@code moov}'s track order), timestamps and
   * duration in its stream's time base, size, ffprobe's flags ({@code K} for a keyframe) and the
   * SHA-256 of its data.
   */
  @Builder
  public record Packet(
      int stream, long pts, long dts, long duration, long size, String flags, String hash) {

    private static Packet parse(String line) {
      var fields = Arrays.asList(line.split(","));
      return builder().stream(Integer.parseInt(fields.get(0)))
          .pts(Long.parseLong(fields.get(1)))
          .dts(Long.parseLong(fields.get(2)))
          .duration(Long.parseLong(fields.get(3)))
          .size(Long.parseLong(fields.get(4)))
          .flags(fields.get(5))
          .hash(fields.get(6))
          .build();
    }

    public boolean isVideo() {
      return stream == VIDEO_STREAM;
    }

    public boolean isAudio() {
      return stream == AUDIO_STREAM;
    }

    public boolean isKeyframe() {
      return flags.startsWith("K");
    }
  }
}
