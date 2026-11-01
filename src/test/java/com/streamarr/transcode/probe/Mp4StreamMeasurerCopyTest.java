package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.SyntheticSampleEntry.AUDIO_SPECIFIC_CONFIG;
import static com.streamarr.transcode.probe.SyntheticSampleEntry.HEVC_PPS;
import static com.streamarr.transcode.probe.SyntheticSampleEntry.HEVC_SPS;
import static com.streamarr.transcode.probe.SyntheticSampleEntry.HEVC_VPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackMeasurement;
import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("MP4 Stream Measurer Copy Tests")
class Mp4StreamMeasurerCopyTest {

  private static final byte[] RAW_AAC_FRAME = {0x21, 0x10};
  private static final byte[] ADTS_FRAME = {(byte) 0xFF, (byte) 0xF1};
  private static final List<Integer> EVERY_HEVC_PARAMETER_SET =
      List.of(HEVC_VPS, HEVC_SPS, HEVC_PPS);

  @TempDir Path temporaryDirectory;

  @ParameterizedTest(name = "{0}")
  @MethodSource("videoKeptByCopy")
  @DisplayName("Should measure the video when a copy delivers its samples as the table lists them")
  void shouldMeasureVideoWhenCopyDeliversItsSamplesAsTableListsThem(
      String codecName, byte[] sampleEntry) throws Exception {
    var video = video(codecName);
    var source = write(videoTrack(sampleEntry));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video));

    assertThat(index.measured()).extracting(TrackMeasurement::measuredBytes).containsExactly(40L);
  }

  static Stream<Arguments> videoKeptByCopy() {
    return Stream.of(
        arguments("h264", SyntheticSampleEntry.avc("avc1", 4, 1, 1)),
        arguments("h264", SyntheticSampleEntry.avc("dva1", 4, 1, 1)),
        arguments("hevc", SyntheticSampleEntry.hevc("hvc1", 4, EVERY_HEVC_PARAMETER_SET)),
        arguments("hevc", SyntheticSampleEntry.hevc("dvh1", 4, EVERY_HEVC_PARAMETER_SET)),
        arguments("mpeg4", SyntheticSampleEntry.visual("mp4v")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("videoResizedByCopy")
  @DisplayName("Should measure nothing when a copy would change the sizes of the video's samples")
  void shouldMeasureNothingWhenCopyWouldChangeSizesOfVideosSamples(
      String condition, String codecName, byte[] sampleEntry) throws Exception {
    var video = video(codecName);
    var source = write(videoTrack(sampleEntry));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video));

    assertThat(index.keyframes()).isInstanceOf(Listed.class);
    assertThat(index.measured()).isEmpty();
  }

  static Stream<Arguments> videoResizedByCopy() {
    return Stream.of(
        arguments(
            "H.264 with parameter sets in its samples",
            "h264",
            SyntheticSampleEntry.avc("avc3", 4, 1, 1)),
        arguments(
            "Dolby Vision H.264 with parameter sets in its samples",
            "h264",
            SyntheticSampleEntry.avc("dvav", 4, 1, 1)),
        arguments(
            "H.264 with 2-byte NAL lengths", "h264", SyntheticSampleEntry.avc("avc1", 2, 1, 1)),
        arguments(
            "H.264 whose record holds no SPS", "h264", SyntheticSampleEntry.avc("avc1", 4, 0, 1)),
        arguments(
            "H.264 whose record holds no PPS", "h264", SyntheticSampleEntry.avc("avc1", 4, 1, 0)),
        arguments("Annex B H.264", "h264", SyntheticSampleEntry.visual("avc1")),
        arguments(
            "HEVC with parameter sets in its samples",
            "hevc",
            SyntheticSampleEntry.hevc("hev1", 4, EVERY_HEVC_PARAMETER_SET)),
        arguments(
            "Dolby Vision HEVC with parameter sets in its samples",
            "hevc",
            SyntheticSampleEntry.hevc("dvhe", 4, EVERY_HEVC_PARAMETER_SET)),
        arguments(
            "HEVC with 2-byte NAL lengths",
            "hevc",
            SyntheticSampleEntry.hevc("hvc1", 2, EVERY_HEVC_PARAMETER_SET)),
        arguments(
            "HEVC whose record holds no PPS",
            "hevc",
            SyntheticSampleEntry.hevc("hvc1", 4, List.of(HEVC_VPS, HEVC_SPS))),
        arguments("Annex B HEVC", "hevc", SyntheticSampleEntry.visual("hvc1")),
        arguments(
            "AV1, whose padding and temporal delimiter OBUs the muxer drops",
            "av1",
            SyntheticSampleEntry.visual("av01")),
        arguments(
            "VP9, which the muxer passes through its superframe filter",
            "vp9",
            SyntheticSampleEntry.visual("vp09")),
        arguments("H.264 in a track without an stsd", "h264", null));
  }

  @Test
  @DisplayName(
      "Should measure nothing when a track names more than one sample entry for H.264 video")
  void shouldMeasureNothingWhenTrackNamesMoreThanOneSampleEntryForH264Video() throws Exception {
    var entry = SyntheticSampleEntry.avc("avc1", 4, 1, 1);
    var video = video("h264");
    var source = write(videoTrack(entry).sampleEntry(entry));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video));

    assertThat(index.measured()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("audioKeptByCopy")
  @DisplayName("Should measure the audio when a copy delivers its samples as the table lists them")
  void shouldMeasureAudioWhenCopyDeliversItsSamplesAsTableListsThem(
      String condition, TrackStream audio, SyntheticMp4 track) throws Exception {
    var video = video("");
    var source = write(videoTrack(null).secondTrack(track));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video, audio));

    assertThat(index.measured()).extracting(TrackMeasurement::streamIndex).containsExactly(0, 1);
  }

  static Stream<Arguments> audioKeptByCopy() {
    return Stream.of(
        arguments(
            "AAC with an AudioSpecificConfig",
            audio("aac", 48_000),
            aacTrack(SyntheticSampleEntry.mp4aWithEsds(AUDIO_SPECIFIC_CONFIG), RAW_AAC_FRAME)),
        arguments(
            "QuickTime AAC with an AudioSpecificConfig in a wave box",
            audio("aac", 48_000),
            aacTrack(SyntheticSampleEntry.quickTimeMp4aWithEsdsInWave(), RAW_AAC_FRAME)),
        arguments(
            "E-AC-3 whose samples each hold six blocks",
            audio("eac3", 48_000),
            audioTrack(48_000, 1_536).build()),
        arguments(
            "E-AC-3 of six blocks on a 90 kHz timescale",
            audio("eac3", 48_000),
            audioTrack(90_000, 2_880).build()),
        arguments("AC-3", audio("ac3", 48_000), audioTrack(48_000, 1_536).build()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("audioResizedByCopy")
  @DisplayName(
      "Should measure nothing when a copy would change the samples of a required audio stream")
  void shouldMeasureNothingWhenCopyWouldChangeSamplesOfRequiredAudioStream(
      String condition, TrackStream audio, SyntheticMp4 track) throws Exception {
    var video = video("");
    var source = write(videoTrack(null).secondTrack(track));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video, audio));

    assertThat(index.measured()).isEmpty();
  }

  static Stream<Arguments> audioResizedByCopy() {
    return Stream.of(
        arguments(
            "AAC without an esds",
            audio("aac", 48_000),
            aacTrack(SyntheticSampleEntry.audio("mp4a", 0), RAW_AAC_FRAME)),
        arguments(
            "AAC whose esds holds no DecoderSpecificInfo",
            audio("aac", 48_000),
            aacTrack(SyntheticSampleEntry.mp4aWithEsds(null), RAW_AAC_FRAME)),
        arguments(
            "AAC whose DecoderSpecificInfo is empty",
            audio("aac", 48_000),
            aacTrack(SyntheticSampleEntry.mp4aWithEsds(new byte[0]), RAW_AAC_FRAME)),
        arguments(
            "AAC whose esds starts with another descriptor",
            audio("aac", 48_000),
            aacTrack(
                SyntheticSampleEntry.audio(
                    "mp4a", 0, SyntheticMp4.box("esds", new byte[] {0, 0, 0, 0, 4, 0})),
                RAW_AAC_FRAME)),
        arguments(
            "AAC whose ES_Descriptor holds no DecoderConfigDescriptor",
            audio("aac", 48_000),
            aacTrack(
                SyntheticSampleEntry.audio(
                    "mp4a", 0, SyntheticMp4.box("esds", new byte[] {0, 0, 0, 0, 3, 4, 0, 1, 0, 6})),
                RAW_AAC_FRAME)),
        arguments(
            "AAC whose samples are ADTS frames beside an AudioSpecificConfig",
            audio("aac", 48_000),
            aacTrack(SyntheticSampleEntry.mp4aWithEsds(AUDIO_SPECIFIC_CONFIG), ADTS_FRAME)),
        arguments(
            "AAC whose first chunk offset exceeds what a file position can hold",
            audio("aac", 48_000),
            audioTrack(48_000, 1_024)
                .sampleEntry(SyntheticSampleEntry.mp4aWithEsds(AUDIO_SPECIFIC_CONFIG))
                .largeChunkOffset(Long.MIN_VALUE)
                .build()),
        arguments(
            "AAC whose track lists no chunk offsets",
            audio("aac", 48_000),
            audioTrack(48_000, 1_024)
                .sampleEntry(SyntheticSampleEntry.mp4aWithEsds(AUDIO_SPECIFIC_CONFIG))
                .build()),
        arguments(
            "AAC in another sample entry",
            audio("aac", 48_000),
            audioTrack(48_000, 1_024).sampleEntry(SyntheticSampleEntry.audio("enca", 0)).build()),
        arguments(
            "AAC in a sound description of an unknown version",
            audio("aac", 48_000),
            audioTrack(48_000, 1_024).sampleEntry(SyntheticSampleEntry.audio("mp4a", 3)).build()),
        arguments(
            "E-AC-3 whose samples each hold one block",
            audio("eac3", 48_000),
            audioTrack(48_000, 256).build()),
        arguments(
            "E-AC-3 without a sample rate", audio("eac3", 0), audioTrack(48_000, 1_536).build()));
  }

  @Test
  @DisplayName(
      "Should leave out another video stream but measure the required streams when a copy would"
          + " change the other stream's samples")
  void shouldLeaveOutAnotherVideoStreamWhenCopyWouldChangeItsSamples() throws Exception {
    var video = video("h264");
    var other =
        TrackStream.builder()
            .streamIndex(1)
            .trackId(SyntheticMp4.SECOND_TRACK_ID)
            .isRequired(false)
            .codecName("h264")
            .build();
    var source =
        write(
            videoTrack(SyntheticSampleEntry.avc("avc1", 4, 1, 1))
                .secondTrack(
                    SyntheticMp4.builder()
                        .mediaTimescale(1_000)
                        .sampleDurations(4, 500)
                        .sampleEntry(SyntheticSampleEntry.avc("avc3", 4, 1, 1))
                        .build()));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video, other));

    assertThat(index.measured()).extracting(TrackMeasurement::streamIndex).containsExactly(0);
  }

  private static TrackStream video(String codecName) {
    return TrackStream.builder()
        .streamIndex(0)
        .trackId(SyntheticMp4.TRACK_ID)
        .isRequired(true)
        .codecName(codecName)
        .build();
  }

  private static TrackStream audio(String codecName, long sampleRate) {
    return TrackStream.builder()
        .streamIndex(1)
        .trackId(SyntheticMp4.SECOND_TRACK_ID)
        .isRequired(true)
        .codecName(codecName)
        .sampleRate(sampleRate)
        .build();
  }

  /** A video track of four 10-byte keyframes, with this sample entry unless it is null. */
  private static SyntheticMp4.SyntheticMp4Builder videoTrack(byte[] sampleEntry) {
    var track =
        SyntheticMp4.builder().mediaTimescale(1_000).sampleDurations(4, 500).sampleSizes(4, 10);
    if (sampleEntry != null) {
      track.sampleEntry(sampleEntry);
    }

    return track;
  }

  /** An AAC track at 48 kHz whose four 7-byte samples start with these bytes. */
  private static SyntheticMp4 aacTrack(byte[] sampleEntry, byte[] firstBytes) {
    var data = new byte[28];
    System.arraycopy(firstBytes, 0, data, 0, firstBytes.length);
    return audioTrack(48_000, 1_024).sampleEntry(sampleEntry).mediaData(data).build();
  }

  private static SyntheticMp4.SyntheticMp4Builder audioTrack(long timescale, int sampleDuration) {
    return SyntheticMp4.builder()
        .handler("soun")
        .mediaTimescale(timescale)
        .sampleDurations(4, sampleDuration)
        .sampleSizes(4, 7);
  }

  private Path write(SyntheticMp4.SyntheticMp4Builder file) throws Exception {
    return Files.write(temporaryDirectory.resolve("source.mp4"), file.build().bytes());
  }
}
