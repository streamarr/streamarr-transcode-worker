package com.streamarr.transcode.worker.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class X264Options {

  private X264Options() {}

  // The settings x264 writes into its unregistered user data SEI, ended by a NUL byte.
  public static List<String> of(byte[] media) {
    var text = new String(media, StandardCharsets.ISO_8859_1);
    var start = text.indexOf("options: ");
    assertThat(start).as("the x264 options in the output").isNotNegative();
    var end = text.indexOf('\0', start);
    return List.of(text.substring(start + "options: ".length(), end).split(" "));
  }
}
