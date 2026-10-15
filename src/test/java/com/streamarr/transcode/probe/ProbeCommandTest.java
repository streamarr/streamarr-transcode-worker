package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.transcode.probe.ProbeCommand.ProbeTool;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Command Tests")
class ProbeCommandTest {

  @Test
  @DisplayName("Should reject the command when it names no tool")
  void shouldRejectCommandWhenItNamesNoTool() {
    var arguments = List.of("-version");

    assertThatNullPointerException()
        .isThrownBy(() -> new ProbeCommand(null, arguments))
        .withMessageContaining("tool");
  }

  @Test
  @DisplayName("Should reject the command when it has no arguments")
  void shouldRejectCommandWhenItHasNoArguments() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ProbeCommand(ProbeTool.FFMPEG, null))
        .withMessageContaining("arguments");
  }
}
