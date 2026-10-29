package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("UnitTest")
@DisplayName("Video Timing Channel Tests")
@Timeout(30)
class VideoTimingChannelTest {

  // ADR 0013: a timing that runs more than 65,536 packets ahead of the copy fails the attempt.
  private static final int PACKETS_AHEAD_THAT_THE_ADR_ALLOWS = 65_536;
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
  }

  @Test
  @DisplayName(
      "Should end every later wait for a packet's timing when the timing has already ended")
  void shouldEndEveryLaterWaitForAPacketsTimingWhenTheTimingHasAlreadyEnded() {
    channel.copyExited();
    assertEnds(Reason.END_OF_VIDEO_TIMING);

    assertEnds(Reason.END_OF_VIDEO_TIMING);
  }

  @Test
  @DisplayName(
      "Should end the timing, and remove its socket and the socket's directory, when the copying"
          + " FFmpeg exits without connecting")
  void
      shouldEndTheTimingAndRemoveItsSocketAndTheSocketsDirectoryWhenTheCopyingFfmpegExitsWithoutConnecting() {
    var directory = socket().getParent();

    channel.copyExited();

    assertEnds(Reason.END_OF_VIDEO_TIMING);
    assertThat(directory).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should have removed its socket and the socket's directory when closing returns before the"
          + " copying FFmpeg connects")
  void
      shouldHaveRemovedItsSocketAndTheSocketsDirectoryWhenClosingReturnsBeforeTheCopyingFfmpegConnects() {
    var directory = socket().getParent();

    channel.close();

    assertThat(directory).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should give the packets of the copying FFmpeg when it connects, writes and exits at once")
  void shouldGiveThePacketsOfTheCopyingFfmpegWhenItConnectsWritesAndExitsAtOnce()
      throws IOException {
    try (var ffmpeg = connect()) {
      write(ffmpeg, HEADER + RECORD);
    }

    channel.copyExited();

    assertThat(channel.next()).extracting(VideoPacketTiming::duration).isEqualTo(41L);
  }

  @Test
  @DisplayName(
      "Should hold every packet when the copying FFmpeg writes exactly as many packets ahead of the"
          + " copy as ADR 0013 allows")
  void
      shouldHoldEveryPacketWhenTheCopyingFfmpegWritesExactlyAsManyPacketsAheadOfTheCopyAsAdr0013Allows()
          throws IOException {
    try (var ffmpeg = connect()) {
      write(ffmpeg, HEADER + RECORD.repeat(PACKETS_AHEAD_THAT_THE_ADR_ALLOWS));
      awaitHangUp(ffmpeg);
    }

    for (var packet = 0; packet < PACKETS_AHEAD_THAT_THE_ADR_ALLOWS; packet++) {
      assertThat(channel.next()).extracting(VideoPacketTiming::duration).isEqualTo(41L);
    }

    assertEnds(Reason.END_OF_VIDEO_TIMING);
  }

  @Test
  @DisplayName(
      "Should fail as unusable video timing when the copying FFmpeg writes more packets ahead of"
          + " the copy than ADR 0013 allows")
  void
      shouldFailAsUnusableVideoTimingWhenTheCopyingFfmpegWritesMorePacketsAheadOfTheCopyThanAdr0013Allows()
          throws IOException {
    try (var ffmpeg = connect()) {
      write(ffmpeg, HEADER + RECORD.repeat(PACKETS_AHEAD_THAT_THE_ADR_ALLOWS + 1));
      awaitHangUp(ffmpeg);
    }

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
      "Should end the timing when closed while it reads the copying FFmpeg's open connection")
  void shouldEndTheTimingWhenClosedWhileItReadsTheCopyingFfmpegsOpenConnection()
      throws IOException {
    try (var ffmpeg = connect()) {
      write(ffmpeg, HEADER + RECORD);
      assertThat(channel.next()).as("a packet read from the open connection").isNotNull();

      channel.close();

      assertEnds(Reason.END_OF_VIDEO_TIMING);
      awaitHangUp(ffmpeg);
    }
  }

  @Test
  @DisplayName(
      "Should remove its socket and the socket's directory when it accepts the copying FFmpeg's"
          + " connection")
  void shouldRemoveItsSocketAndTheSocketsDirectoryWhenItAcceptsTheCopyingFfmpegsConnection()
      throws IOException {
    var socket = socket();
    try (var ffmpeg = connect()) {
      write(ffmpeg, HEADER + RECORD);

      assertThat(channel.next()).as("a packet read from the accepted connection").isNotNull();

      assertThat(socket).doesNotExist();
      assertThat(socket.getParent()).doesNotExist();
      assertThat(ffmpeg.isConnected()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should keep the waiting thread's interrupt when an interrupt ends its wait for a packet's"
          + " timing")
  void shouldKeepTheWaitingThreadsInterruptWhenAnInterruptEndsItsWaitForAPacketsTiming() {
    Thread.currentThread().interrupt();

    assertThatExceptionOfType(InterruptedIOException.class).isThrownBy(channel::next);
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  @DisplayName("Should listen in a directory that only the worker's user can enter when opened")
  void shouldListenInADirectoryThatOnlyTheWorkersUserCanEnterWhenOpened() throws IOException {
    assertThat(Files.getPosixFilePermissions(socket().getParent()))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
  }

  private void assertEnds(Reason reason) {
    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(channel::next)
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(reason);
  }

  // The channel closes its end of the connection only after it has given the timing its end.
  private static void awaitHangUp(SocketChannel ffmpeg) throws IOException {
    ffmpeg.shutdownOutput();
    var discarded = ByteBuffer.allocate(64);
    while (ffmpeg.read(discarded) >= 0) {
      discarded.clear();
    }
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
