package com.streamarr.transcode.worker;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Warns when the JVM converts filenames with a charset other than UTF-8.
 *
 * <p>{@code sun.jnu.encoding} follows the process locale, not {@code file.encoding}, and it is the
 * charset Java uses to resolve source keys and to pass paths to FFprobe and FFmpeg.
 */
@Slf4j
final class NativeFilenameEncodingCheck {

  private static final List<String> LOCALE_PRECEDENCE = List.of("LC_ALL", "LC_CTYPE", "LANG");

  private final String nativeEncoding;
  private final Map<String, String> environment;

  NativeFilenameEncodingCheck(String nativeEncoding, Map<String, String> environment) {
    this.nativeEncoding = nativeEncoding;
    this.environment = environment;
  }

  void warnUnlessUtf8() {
    if (isUtf8(nativeEncoding)) {
      return;
    }

    var charset = nativeEncoding.isBlank() ? "an unknown charset" : nativeEncoding;
    log.warn(
        "The worker converts filenames with {} rather than UTF-8 because the effective locale is"
            + " {}. Media whose names contain non-ASCII characters cannot be probed or streamed."
            + " Start the worker under a UTF-8 locale such as LANG=C.UTF-8, and remove or change"
            + " any LC_ALL or LC_CTYPE value that is not UTF-8.",
        charset,
        effectiveLocale());
  }

  private String effectiveLocale() {
    return LOCALE_PRECEDENCE.stream()
        .filter(name -> !environment.getOrDefault(name, "").isEmpty())
        .map(name -> name + "=" + environment.get(name))
        .findFirst()
        .orElse("the C locale because no locale variable is set");
  }

  private static boolean isUtf8(String charsetName) {
    try {
      return StandardCharsets.UTF_8.equals(Charset.forName(charsetName));
    } catch (IllegalArgumentException _) {
      return false;
    }
  }
}
