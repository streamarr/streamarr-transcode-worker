package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

/** The digest an approving review binds, computed by the buildpack's own shell library. */
final class FfmpegInventoryDigest {

  private static final Path LIBRARY = Path.of("buildpacks/ffmpeg/lib").toAbsolutePath();

  private FfmpegInventoryDigest() {}

  static String of(Path buildpack) throws Exception {
    var process =
        new ProcessBuilder(
                "bash",
                "-c",
                ". \"$1/checksum.sh\"; . \"$1/notices.sh\"; ffmpeg_notices_digest \"$2\"",
                "--",
                LIBRARY.toString(),
                buildpack.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
    var digest = new String(process.getInputStream().readAllBytes()).trim();
    assertThat(process.waitFor()).as(digest).isZero();
    return digest;
  }
}
