package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Media Source Channel Tests")
class MediaSourceChannelTest {

  @TempDir Path mediaRoot;

  @Test
  @DisplayName("Should read the file's bytes when the media source is a regular file")
  void shouldReadTheFilesBytesWhenTheMediaSourceIsARegularFile() throws Exception {
    var source = Files.write(mediaRoot.resolve("movie.mp4"), new byte[] {1, 2, 3});

    try (var channel = MediaSourceChannel.openForReading(source)) {
      var bytes = ByteBuffer.allocate(3);
      channel.read(bytes);

      assertThat(bytes.array()).containsExactly(1, 2, 3);
    }
  }

  @Test
  @DisplayName("Should refuse to open the media source when a soft link has replaced it")
  void shouldRefuseToOpenTheMediaSourceWhenASoftLinkHasReplacedIt() throws Exception {
    var outside = Files.write(mediaRoot.resolve("secret"), new byte[] {4, 5, 6});
    var source = Files.createSymbolicLink(mediaRoot.resolve("movie.mp4"), outside);

    assertThatThrownBy(() -> MediaSourceChannel.openForReading(source).close())
        .isInstanceOf(IOException.class);
  }
}
