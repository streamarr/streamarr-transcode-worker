package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * The Unix domain socket to which the FFmpeg that copies the source writes the framecrc of the
 * copy's video packets, as a second output beside the copy, and the timing of each packet read from
 * it. The socket lies in a directory that only the worker's user can enter, and both are removed
 * once the channel stops listening. A virtual thread reads the framecrc as FFmpeg writes it, so
 * that FFmpeg never waits for the copy's reader to take a packet's timing.
 */
@Slf4j
final class VideoTimingChannel implements VideoPacketTimings, AutoCloseable {

  /**
   * The most packets whose timing the channel holds before the copy's reader takes it. FFmpeg's
   * interleaving holds back at most ten seconds of the copy's packets, a few hundred at the frame
   * rates of video.
   */
  private static final int MAXIMUM_PACKETS_AHEAD = 65_536;

  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

  private final Path directory;
  private final Path socket;
  private final ServerSocketChannel listener;
  private final Selector selector;
  private final BlockingDeque<Timing> timings = new LinkedBlockingDeque<>();
  private final AtomicReference<Optional<SocketChannel>> connection =
      new AtomicReference<>(Optional.empty());
  private volatile boolean listening = true;
  private volatile boolean closed;

  @Builder(access = AccessLevel.PRIVATE)
  private VideoTimingChannel(
      Path directory, Path socket, ServerSocketChannel listener, Selector selector) {
    this.directory = directory;
    this.socket = socket;
    this.listener = listener;
    this.selector = selector;
  }

  /** Listens on a new socket, and reads the framecrc of the first FFmpeg that connects to it. */
  static VideoTimingChannel open() throws IOException {
    var directory = Files.createTempDirectory("sts", OWNER_ONLY);
    var socket = directory.resolve("v");
    try {
      var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      listener.bind(UnixDomainSocketAddress.of(socket));
      listener.configureBlocking(false);
      var selector = Selector.open();
      listener.register(selector, SelectionKey.OP_ACCEPT);
      var channel =
          VideoTimingChannel.builder()
              .directory(directory)
              .socket(socket)
              .listener(listener)
              .selector(selector)
              .build();
      Thread.ofVirtual().name("ffmpeg-video-timing").start(channel::read);
      return channel;
    } catch (IOException e) {
      Files.deleteIfExists(socket);
      Files.deleteIfExists(directory);
      throw e;
    }
  }

  /** The URL of the FFmpeg output that writes the framecrc to the socket. */
  String url() {
    return "unix:" + socket;
  }

  @Override
  public VideoPacketTiming next() throws IOException {
    try {
      return switch (timings.takeFirst()) {
        case Timing.Packet(var packet) -> packet;
        case Timing.End end -> {
          timings.putFirst(end);
          throw end.failure();
        }
      };
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("interrupted while waiting for a video packet's timing");
    }
  }

  /**
   * Stops listening once the FFmpeg that copies the source has exited; the framecrc of an FFmpeg
   * that connected before it exited is still read.
   */
  void copyExited() {
    listening = false;
    selector.wakeup();
  }

  /** Stops listening and reading, and returns once the socket and its directory are removed. */
  @Override
  public void close() {
    closed = true;
    copyExited();
    connection.get().ifPresent(VideoTimingChannel::closeQuietly);
    removeSocket();
  }

  private void read() {
    try (listener;
        selector) {
      var accepted = accept();
      removeSocket();
      if (accepted.isPresent()) {
        readFramecrc(accepted.orElseThrow());
        return;
      }

      end(Reason.END_OF_VIDEO_TIMING, "the FFmpeg that copies the source never connected");
    } catch (FragmentedMp4Exception e) {
      timings.add(new Timing.End(e));
    } catch (IOException e) {
      end(Reason.END_OF_VIDEO_TIMING, "reading the video packets' timing failed: " + e);
    } finally {
      removeSocket();
    }
  }

  // The exit is read before the accept, so a connection that FFmpeg made before it exited is
  // accepted, and select returns once copyExited wakes it.
  private Optional<SocketChannel> accept() throws IOException {
    while (true) {
      var exited = !listening;
      var accepted = listener.accept();
      if (accepted != null) {
        return Optional.of(accepted);
      }

      if (exited) {
        return Optional.empty();
      }

      selector.select();
      selector.selectedKeys().clear();
    }
  }

  private void readFramecrc(SocketChannel accepted) throws IOException {
    try (accepted) {
      accepted.configureBlocking(true);
      // close() closes the connection it finds, so one published after it reads closed here.
      connection.set(Optional.of(accepted));
      if (closed) {
        end(Reason.END_OF_VIDEO_TIMING, "the video packets' timing was closed");
        return;
      }

      var framecrc = new FramecrcTimings(Channels.newInputStream(accepted));
      for (var packet = framecrc.next(); packet.isPresent(); packet = framecrc.next()) {
        if (timings.size() >= MAXIMUM_PACKETS_AHEAD) {
          timings.clear();
          end(
              Reason.UNUSABLE_VIDEO_TIMING,
              "the copying FFmpeg wrote the timing of more than "
                  + MAXIMUM_PACKETS_AHEAD
                  + " video packets ahead of the copy");
          return;
        }

        timings.add(new Timing.Packet(packet.orElseThrow()));
      }

      end(Reason.END_OF_VIDEO_TIMING, "the copying FFmpeg's video packet timing ended");
    }
  }

  private void end(Reason reason, String detail) {
    timings.add(new Timing.End(new FragmentedMp4Exception(reason, detail)));
  }

  private void removeSocket() {
    try {
      Files.deleteIfExists(socket);
      Files.deleteIfExists(directory);
    } catch (IOException e) {
      log.warn("Could not remove the video timing socket {}", socket, e);
    }
  }

  private static void closeQuietly(SocketChannel channel) {
    try {
      channel.close();
    } catch (IOException _) {
      // Closing only stops the read, which ends the timing either way.
    }
  }

  private sealed interface Timing {
    record Packet(VideoPacketTiming timing) implements Timing {}

    record End(FragmentedMp4Exception failure) implements Timing {}
  }
}
