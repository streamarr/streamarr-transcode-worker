package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Native Filename Encoding Check Tests")
class NativeFilenameEncodingCheckTest {

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "utf8"})
  @DisplayName("Should stay silent when native filenames are encoded as UTF-8")
  void shouldStaySilentWhenNativeFilenamesAreEncodedAsUtf8(String encoding) {
    var warnings = warningsFrom(new NativeFilenameEncodingCheck(encoding, Map.of()));

    assertThat(warnings).isEmpty();
  }

  @Test
  @DisplayName("Should name the overriding locale variable when native filenames are ASCII")
  void shouldNameOverridingLocaleVariableWhenNativeFilenamesAreAscii() {
    var check =
        new NativeFilenameEncodingCheck(
            "ANSI_X3.4-1968", Map.of("LC_ALL", "POSIX", "LANG", "C.UTF-8"));

    var warnings = warningsFrom(check);

    assertThat(warnings)
        .singleElement()
        .asString()
        .contains("ANSI_X3.4-1968")
        .contains("LC_ALL=POSIX")
        .contains("C.UTF-8");
  }

  @Test
  @DisplayName("Should report an unset locale when no locale variable is set")
  void shouldReportUnsetLocaleWhenNoLocaleVariableIsSet() {
    var warnings = warningsFrom(new NativeFilenameEncodingCheck("ANSI_X3.4-1968", Map.of()));

    assertThat(warnings).singleElement().asString().contains("no locale variable is set");
  }

  @Test
  @DisplayName("Should warn when the native filename encoding is unknown")
  void shouldWarnWhenNativeFilenameEncodingIsUnknown() {
    var warnings = warningsFrom(new NativeFilenameEncodingCheck("", Map.of("LANG", "C.UTF-8")));

    assertThat(warnings).singleElement().asString().contains("an unknown charset");
  }

  private static List<String> warningsFrom(NativeFilenameEncodingCheck check) {
    var logger = (Logger) LoggerFactory.getLogger(NativeFilenameEncodingCheck.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      check.warnUnlessUtf8();
    } finally {
      logger.detachAppender(appender);
    }

    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
