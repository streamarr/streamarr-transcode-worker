package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packets;
import static com.streamarr.transcode.probe.FirstReads.withPresentationTime;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Presentation Times Tests")
class FfprobeExecutorPresentationTimesTest {

  private static final String HEVC = "\"codec_name\":\"hevc\"";
  private static final String SLICE_SAMPLE = lengthPrefixed("02 01 d0 09 7e");

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "h264-sei-1.h264, false",
    "hevc-dovi-p81-fate.hevc, false",
    "hevc-dovi-p5-rpus-ahead.hevc, false",
    "avi-h264-unknown-extradata.avi, false",
    "mp4-sdr8-bt709.mp4, true",
    "mp4-dovi-p5-hev1.mp4, true",
    "mkv-pipe-hevc-10bit.mkv, true",
    "mpegts-hevc-ac3.ts, true"
  })
  @DisplayName(
      "Should report whether the first packets carry presentation times as the demuxer gives them"
          + " when the probe reads the chosen video stream's packets")
  void
      shouldReportWhetherTheFirstPacketsCarryPresentationTimesAsTheDemuxerGivesThemWhenTheProbeReadsTheChosenVideoStreamsPackets(
          String fixture, boolean carried) {
    var video = streamOf(fixture, 0);

    assertThat(video.hasFirstPacketsCarryPresentationTimes()).isTrue();
    assertThat(video.getFirstPacketsCarryPresentationTimes()).isEqualTo(carried);
  }

  @Test
  @DisplayName(
      "Should report packets without presentation times when one packet that the read printed has"
          + " no pts")
  void shouldReportPacketsWithoutPresentationTimesWhenOnePacketThatTheReadPrintedHasNoPts() {
    var printed =
        withPresentationTime(
            withPresentationTime(packets(SLICE_SAMPLE, SLICE_SAMPLE, SLICE_SAMPLE), 0, 0), 2, 2);

    var video = videoReading(printed);

    assertThat(video.hasFirstPacketsCarryPresentationTimes()).isTrue();
    assertThat(video.getFirstPacketsCarryPresentationTimes()).isFalse();
  }

  @Test
  @DisplayName(
      "Should report packets with presentation times when every packet that the read printed has a"
          + " pts")
  void shouldReportPacketsWithPresentationTimesWhenEveryPacketThatTheReadPrintedHasAPts() {
    var video = videoReading(packetsWithPresentationTimes());

    assertThat(video.getFirstPacketsCarryPresentationTimes()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"{\"packets\":[]}", "{}", "not json"})
  @DisplayName(
      "Should report no presentation-time fact when the first-packets read prints no packet")
  void shouldReportNoPresentationTimeFactWhenTheFirstPacketsReadPrintsNoPacket(String printed) {
    assertThat(videoReading(printed).hasFirstPacketsCarryPresentationTimes()).isFalse();
  }

  @Test
  @DisplayName("Should report no presentation-time fact when the first-packets read fails")
  void shouldReportNoPresentationTimeFactWhenTheFirstPacketsReadFails() {
    var video =
        probe(Answers.builder().ffprobeOutput(output(HEVC)).firstFrames(frames("")).build())
            .getStreams(0);

    assertThat(video.getFirstFramesRead()).isTrue();
    assertThat(video.hasFirstPacketsCarryPresentationTimes()).isFalse();
  }

  @Test
  @DisplayName(
      "Should report no presentation-time fact when the first-packets read exits with an error"
          + " after printing packets")
  void shouldReportNoPresentationTimeFactWhenTheFirstPacketsReadExitsWithAnErrorAfterPrinting() {
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(output(HEVC))
                    .firstFrames(frames("", "", ""))
                    .firstPackets(packetsWithPresentationTimes())
                    .firstPacketsExitCode(1)
                    .build())
            .getStreams(0);

    assertThat(video.hasFirstPacketsCarryPresentationTimes()).isFalse();
  }

  @Test
  @Timeout(60)
  @DisplayName(
      "Should report no presentation-time fact when the first-packets read prints more than 64 MiB")
  void shouldReportNoPresentationTimeFactWhenTheFirstPacketsReadPrintsMoreThan64MiB() {
    var printed = packetsWithPresentationTimes();
    var padded =
        printed.substring(0, printed.length() - 1)
            + " ".repeat((64 << 20) + 1 - printed.length())
            + "}";

    assertThat(videoReading(padded).hasFirstPacketsCarryPresentationTimes()).isFalse();
  }

  @Test
  @DisplayName("Should report no presentation-time fact when the probe reads no first frames")
  void shouldReportNoPresentationTimeFactWhenTheProbeReadsNoFirstFrames() {
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(output(HEVC))
                    .firstPackets(withPresentationTime(packets(SLICE_SAMPLE), 0, 0))
                    .build())
            .getStreams(0);

    assertThat(video.getFirstFramesRead()).isFalse();
    assertThat(video.hasFirstPacketsCarryPresentationTimes()).isFalse();
  }

  @Test
  @DisplayName("Should report no presentation-time fact when the stream is audio")
  void shouldReportNoPresentationTimeFactWhenTheStreamIsAudio() {
    var audio = streamOf("mkv-pipe-hevc-10bit.mkv", 1);

    assertThat(audio.hasFirstPacketsCarryPresentationTimes()).isFalse();
  }

  @Test
  @DisplayName(
      "Should report no presentation-time fact when the probe walks no packets of the codec")
  void shouldReportNoPresentationTimeFactWhenTheProbeWalksNoPacketsOfTheCodec() {
    // The probe reads packets only of H.264, HEVC and AV1.
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(output("\"codec_name\":\"vp9\""))
                    .firstFrames(frames("", "", ""))
                    .firstPackets(packetsWithPresentationTimes())
                    .build())
            .getStreams(0);

    assertThat(video.getFirstFramesRead()).isTrue();
    assertThat(video.hasFirstPacketsCarryPresentationTimes()).isFalse();
  }

  @Test
  @DisplayName(
      "Should report no presentation-time fact for a video stream when the probe chose another")
  void shouldReportNoPresentationTimeFactForAVideoStreamWhenTheProbeChoseAnother() {
    var media =
        probe(
            Answers.builder()
                .ffprobeOutput(
                    """
                    {"streams":[
                      {"index":0,"codec_type":"video","codec_name":"hevc","time_base":"1/1000"},
                      {"index":1,"codec_type":"video","codec_name":"hevc","time_base":"1/1000"}]}
                    """)
                .firstFrames(frames("", "", ""))
                .firstPackets(packetsWithPresentationTimes())
                .build());

    assertThat(media.getStreams(0).getFirstPacketsCarryPresentationTimes()).isTrue();
    assertThat(media.getStreams(1).hasFirstPacketsCarryPresentationTimes()).isFalse();
  }

  /** Three packets, each with a pts. */
  private static String packetsWithPresentationTimes() {
    var printed = packets(SLICE_SAMPLE, SLICE_SAMPLE, SLICE_SAMPLE);
    for (var packet = 0; packet < 3; packet++) {
      printed = withPresentationTime(printed, packet, packet * 1001L);
    }

    return printed;
  }

  private static ProbeStreamInfo videoReading(String firstPackets) {
    return probe(
            Answers.builder()
                .ffprobeOutput(output(HEVC))
                .firstFrames(frames("", "", ""))
                .firstPackets(firstPackets)
                .build())
        .getStreams(0);
  }
}
