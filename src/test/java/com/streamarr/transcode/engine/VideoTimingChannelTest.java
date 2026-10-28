package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Video Timing Channel Tests")
class VideoTimingChannelTest {

  private static final Duration LIMIT = Duration.ofSeconds(10);
  private static final String HEADER = "#tb 0: 1/1000\n";
  private static final String RECORD =
      "0,          0,         42,       41,     7369, 0x7e1c3b5a\n";

  private final VideoTimingChannel channel = VideoTimingChannel.open();

  VideoTimingChannelTest() throws IOException {}

  @AfterEach
  void closeChannel() {
    channel.close();
  }

  @Test
  @DisplayName(
      "Should give each packet in the order the copying FFmpeg writes them, and then end, when"
          + " that FFmpeg closes its connection")
  void shouldGiveEachPacketInTheOrderTheCopyingFfmpegWritesThemAndThenEndWhenThatFfmpegClosesIt()
      throws IOException {
    try (var ffmpeg = connect()) {
      write(
          ffmpeg,
          HEADER + RECORD + "0,         41,        250,       41,     6404, 0x0b4f1c2d, F=0x0\n");
    }

    assertThat(channel.next()).extracting(VideoPacketTiming::keyframe).isEqualTo(true);
    assertThat(channel.next()).extracting(VideoPacketTiming::keyframe).isEqualTo(false);
    assertEnds(Reason.END_OF_VIDEO_TIMING);
    assertEnds(Reason.END_OF_VIDEO_TIMING);
  }

  @Test
  @DisplayName("Should end the timing when the copying FFmpeg exits without connecting")
  void shouldEndTheTimingWhenTheCopyingFfmpegExitsWithoutConnecting() {
    channel.copyExited();

    assertEnds(Reason.END_OF_VIDEO_TIMING);
  }

  @Test
  @DisplayName(
      "Should give the packets of a copying FFmpeg that connected, wrote and exited at once")
  void shouldGiveThePacketsOfACopyingFfmpegThatConnectedWroteAndExitedAtOnce() throws IOException {
    try (var ffmpeg = connect()) {
      write(ffmpeg, HEADER + RECORD);
    }

    channel.copyExited();

    assertThat(channel.next()).extracting(VideoPacketTiming::duration).isEqualTo(41L);
  }

  @Test
  @DisplayName(
      "Should fail as unusable video timing when the copying FFmpeg writes more packets than the"
          + " channel holds ahead of the copy")
  void shouldFailAsUnusableVideoTimingWhenTheCopyingFfmpegWritesMorePacketsThanTheChannelHolds()
      throws IOException {
    try (var ffmpeg = connect()) {
      write(ffmpeg, HEADER + RECORD.repeat(VideoTimingChannel.MAXIMUM_PACKETS_AHEAD + 1));
    }

    awaitSocketRemoved();
    assertEnds(Reason.UNUSABLE_VIDEO_TIMING);
  }

  @Test
  @DisplayName("Should fail as unusable video timing when the copying FFmpeg writes no framecrc")
  void shouldFailAsUnusableVideoTimingWhenTheCopyingFfmpegWritesNoFramecrc() throws IOException {
    try (var ffmpeg = connect()) {
      write(ffmpeg, "frame=   48 fps=0.0 q=-1.0 size=     138kB\n");
    }

    assertEnds(Reason.UNUSABLE_VIDEO_TIMING);
  }

  @Test
  @DisplayName(
      "Should end the timing, and remove its socket, when closed while the copying FFmpeg's"
          + " connection stays open")
  void shouldEndTheTimingAndRemoveItsSocketWhenClosedWhileTheCopyingFfmpegsConnectionStaysOpen()
      throws IOException {
    try (var ffmpeg = connect()) {
      write(ffmpeg, HEADER);

      channel.close();

      assertEnds(Reason.END_OF_VIDEO_TIMING);
      awaitSocketRemoved();
    }
  }

  @Test
  @DisplayName("Should remove its socket and the socket's directory once the timing ends")
  void shouldRemoveItsSocketAndTheSocketsDirectoryOnceTheTimingEnds() throws IOException {
    var directory = socket().getParent();
    assertThat(directory).isDirectory();
    try (var ffmpeg = connect()) {
      write(ffmpeg, HEADER);
    }

    assertEnds(Reason.END_OF_VIDEO_TIMING);
    await().atMost(LIMIT).until(() -> Files.notExists(directory));
  }

  private void assertEnds(Reason reason) {
    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(channel::next)
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(reason);
  }

  private void awaitSocketRemoved() {
    var socket = socket();
    await().atMost(LIMIT).until(() -> Files.notExists(socket));
  }

  private SocketChannel connect() throws IOException {
    return SocketChannel.open(UnixDomainSocketAddress.of(socket()));
  }

  private Path socket() {
    assertThat(channel.url()).startsWith("unix:");
    return Path.of(channel.url().substring("unix:".length()));
  }

  private static void write(SocketChannel ffmpeg, String framecrc) throws IOException {
    var bytes = ByteBuffer.wrap(framecrc.getBytes(StandardCharsets.US_ASCII));
    while (bytes.hasRemaining()) {
      ffmpeg.write(bytes);
    }
  }
}
