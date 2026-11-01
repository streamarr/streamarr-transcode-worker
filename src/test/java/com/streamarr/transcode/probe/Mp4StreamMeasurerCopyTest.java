package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.SyntheticSampleEntry.AUDIO_SPECIFIC_CONFIG;
import static com.streamarr.transcode.probe.SyntheticSampleEntry.HEVC_SPS;
import static com.streamarr.transcode.probe.SyntheticSampleEntry.HEVC_VPS;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackMeasurement;
import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("MP4 Stream Measurer Copy Tests")
class Mp4StreamMeasurerCopyTest {

  private static final byte[] RAW_AAC_FRAME = {0x21, 0x10};
  private static final byte[] ADTS_FRAME = {(byte) 0xFF, (byte) 0xF1};
  private static final int USER_DATA_UNREGISTERED = 5;
  private static final int MASTERING_DISPLAY = 137;
  private static final int LEVEL_5_1 = 51;

  @TempDir Path temporaryDirectory;

  @ParameterizedTest(name = "{0}")
  @MethodSource("videoKeptByCopy")
  @DisplayName("Should measure the video when a copy delivers its samples as the table lists them")
  void shouldMeasureVideoWhenCopyDeliversItsSamplesAsTableListsThem(VideoCase copied)
      throws Exception {
    var video = video(copied.codecName());
    var source = write(videoTrack(copied.sampleEntry()));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video));

    assertThat(index.measured()).extracting(TrackMeasurement::measuredBytes).containsExactly(40L);
  }

  static Stream<VideoCase> videoKeptByCopy() {
    return Stream.of(
        VideoCase.builder()
            .condition("H.264 in an avc1 entry")
            .codecName("h264")
            .sampleEntry(SyntheticSampleEntry.avc().build())
            .build(),
        VideoCase.builder()
            .condition("Dolby Vision H.264 in a dva1 entry")
            .codecName("h264")
            .sampleEntry(SyntheticSampleEntry.avc().type("dva1").build())
            .build(),
        VideoCase.builder()
            .condition("HEVC in an hvc1 entry")
            .codecName("hevc")
            .sampleEntry(SyntheticSampleEntry.hevc().build())
            .build(),
        VideoCase.builder()
            .condition("Dolby Vision HEVC in a dvh1 entry")
            .codecName("hevc")
            .sampleEntry(SyntheticSampleEntry.hevc().type("dvh1").build())
            .build(),
        VideoCase.builder()
            .condition("HEVC whose record holds only a user data unregistered SEI message")
            .codecName("hevc")
            .sampleEntry(SyntheticSampleEntry.hevcWithSeiMessage(USER_DATA_UNREGISTERED))
            .build(),
        VideoCase.builder()
            .condition("MPEG-4 Part 2 video")
            .codecName("mpeg4")
            .sampleEntry(SyntheticSampleEntry.visual("mp4v"))
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("videoResizedByCopy")
  @DisplayName("Should measure nothing when a copy would change the sizes of the video's samples")
  void shouldMeasureNothingWhenCopyWouldChangeSizesOfVideosSamples(VideoCase resized)
      throws Exception {
    var video = video(resized.codecName());
    var source = write(videoTrack(resized.sampleEntry()));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video));

    assertThat(index.keyframes()).isInstanceOf(Listed.class);
    assertThat(index.measured()).isEmpty();
  }

  static Stream<VideoCase> videoResizedByCopy() {
    return Stream.of(
        h264("H.264 with parameter sets in its samples", SyntheticSampleEntry.avc().type("avc3")),
        h264(
            "Dolby Vision H.264 with parameter sets in its samples",
            SyntheticSampleEntry.avc().type("dvav")),
        h264("H.264 with 2-byte NAL lengths", SyntheticSampleEntry.avc().lengthBytes(2)),
        h264(
            "H.264 whose record holds no SPS", SyntheticSampleEntry.avc().sequenceParameterSets(0)),
        h264("H.264 whose record holds no PPS", SyntheticSampleEntry.avc().pictureParameterSets(0)),
        VideoCase.builder()
            .condition("Annex B H.264")
            .codecName("h264")
            .sampleEntry(SyntheticSampleEntry.visual("avc1"))
            .build(),
        h264(
            "H.264 whose record header names another level than its SPS",
            SyntheticSampleEntry.avc().levelIdc(LEVEL_5_1)),
        hevc("HEVC with parameter sets in its samples", SyntheticSampleEntry.hevc().type("hev1")),
        hevc(
            "Dolby Vision HEVC with parameter sets in its samples",
            SyntheticSampleEntry.hevc().type("dvhe")),
        hevc("HEVC with 2-byte NAL lengths", SyntheticSampleEntry.hevc().lengthBytes(2)),
        hevc(
            "HEVC whose record holds no PPS",
            SyntheticSampleEntry.hevc().unitTypes(List.of(HEVC_VPS, HEVC_SPS))),
        VideoCase.builder()
            .condition("Annex B HEVC")
            .codecName("hevc")
            .sampleEntry(SyntheticSampleEntry.visual("hvc1"))
            .build(),
        VideoCase.builder()
            .condition("HEVC whose record holds a mastering display SEI message")
            .codecName("hevc")
            .sampleEntry(SyntheticSampleEntry.hevcWithSeiMessage(MASTERING_DISPLAY))
            .build(),
        VideoCase.builder()
            .condition("AV1, whose padding and temporal delimiter OBUs the muxer drops")
            .codecName("av1")
            .sampleEntry(SyntheticSampleEntry.visual("av01"))
            .build(),
        VideoCase.builder()
            .condition("VP9, which the muxer passes through its superframe filter")
            .codecName("vp9")
            .sampleEntry(SyntheticSampleEntry.visual("vp09"))
            .build(),
        VideoCase.builder()
            .condition("H.264 whose avcC box is empty")
            .codecName("h264")
            .sampleEntry(SyntheticSampleEntry.visual("avc1", SyntheticMp4.box("avcC")))
            .build(),
        VideoCase.builder()
            .condition("H.264 in a track without an stsd")
            .codecName("h264")
            .build());
  }

  @Test
  @DisplayName(
      "Should measure nothing when a track names more than one sample entry for H.264 video")
  void shouldMeasureNothingWhenTrackNamesMoreThanOneSampleEntryForH264Video() throws Exception {
    var entry = SyntheticSampleEntry.avc().build();
    var video = video("h264");
    var source = write(videoTrack(entry).sampleEntry(entry));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video));

    assertThat(index.measured()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("audioKeptByCopy")
  @DisplayName("Should measure the audio when a copy delivers its samples as the table lists them")
  void shouldMeasureAudioWhenCopyDeliversItsSamplesAsTableListsThem(AudioCase copied)
      throws Exception {
    var video = video("");
    var source = write(videoTrack(null).secondTrack(copied.track()));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video, copied.stream()));

    assertThat(index.measured()).extracting(TrackMeasurement::streamIndex).containsExactly(0, 1);
  }

  static Stream<AudioCase> audioKeptByCopy() {
    return Stream.of(
        AudioCase.builder()
            .condition("AAC with an AudioSpecificConfig")
            .codecName("aac")
            .track(
                aacTrack(SyntheticSampleEntry.mp4aWithEsds(AUDIO_SPECIFIC_CONFIG), RAW_AAC_FRAME))
            .build(),
        AudioCase.builder()
            .condition("QuickTime AAC with an AudioSpecificConfig in a wave box")
            .codecName("aac")
            .track(aacTrack(SyntheticSampleEntry.quickTimeMp4aWithEsdsInWave(), RAW_AAC_FRAME))
            .build(),
        AudioCase.builder()
            .condition("QuickTime AAC with an AudioSpecificConfig in a version 2 sound description")
            .codecName("aac")
            .track(
                aacTrack(
                    SyntheticSampleEntry.audio(
                        "mp4a", 2, SyntheticSampleEntry.esds(0, AUDIO_SPECIFIC_CONFIG)),
                    RAW_AAC_FRAME))
            .build(),
        AudioCase.builder()
            .condition("E-AC-3 whose samples each hold six blocks")
            .codecName("eac3")
            .sampleRate(48_000)
            .track(audioTrack(48_000, 1_536).build())
            .build(),
        AudioCase.builder()
            .condition("E-AC-3 of six blocks on a 90 kHz timescale")
            .codecName("eac3")
            .sampleRate(48_000)
            .track(audioTrack(90_000, 2_880).build())
            .build(),
        AudioCase.builder()
            .condition("AC-3")
            .codecName("ac3")
            .track(audioTrack(48_000, 1_536).build())
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("audioResizedByCopy")
  @DisplayName(
      "Should measure nothing when a copy would change the samples of a required audio stream")
  void shouldMeasureNothingWhenCopyWouldChangeSamplesOfRequiredAudioStream(AudioCase resized)
      throws Exception {
    var video = video("");
    var source = write(videoTrack(null).secondTrack(resized.track()));

    var index = Mp4SyncSampleReader.read(source, video, List.of(video, resized.stream()));

    assertThat(index.measured()).isEmpty();
  }

  static Stream<AudioCase> audioResizedByCopy() {
    return Stream.of(
        aac("AAC without an esds", aacTrack(SyntheticSampleEntry.audio("mp4a", 0), RAW_AAC_FRAME)),
        aac(
            "AAC whose esds holds no DecoderSpecificInfo",
            aacTrack(SyntheticSampleEntry.mp4aWithEsds(null), RAW_AAC_FRAME)),
        aac(
            "AAC whose DecoderSpecificInfo is empty",
            aacTrack(SyntheticSampleEntry.mp4aWithEsds(new byte[0]), RAW_AAC_FRAME)),
        aac(
            "AAC whose esds starts with another descriptor",
            aacTrack(
                SyntheticSampleEntry.audio(
                    "mp4a", 0, SyntheticMp4.box("esds", new byte[] {0, 0, 0, 0, 4, 0})),
                RAW_AAC_FRAME)),
        aac(
            "AAC whose ES_Descriptor holds no DecoderConfigDescriptor",
            aacTrack(
                SyntheticSampleEntry.audio(
                    "mp4a", 0, SyntheticMp4.box("esds", new byte[] {0, 0, 0, 0, 3, 4, 0, 1, 0, 6})),
                RAW_AAC_FRAME)),
        aac(
            "AAC whose samples are ADTS frames beside an AudioSpecificConfig",
            aacTrack(SyntheticSampleEntry.mp4aWithEsds(AUDIO_SPECIFIC_CONFIG), ADTS_FRAME)),
        aac(
            "AAC whose ADTS frames a co64 locates",
            audioTrack(48_000, 1_024)
                .sampleEntry(SyntheticSampleEntry.mp4aWithEsds(AUDIO_SPECIFIC_CONFIG))
                .mediaData(firstSampleStartingWith(ADTS_FRAME))
                .largeChunkOffsets(true)
                .build()),
        aac(
            "AAC whose first chunk offset exceeds what a file position can hold",
            audioTrack(48_000, 1_024)
                .sampleEntry(SyntheticSampleEntry.mp4aWithEsds(AUDIO_SPECIFIC_CONFIG))
                .largeChunkOffset(Long.MIN_VALUE)
                .build()),
        aac(
            "AAC whose track lists no chunk offsets",
            audioTrack(48_000, 1_024)
                .sampleEntry(SyntheticSampleEntry.mp4aWithEsds(AUDIO_SPECIFIC_CONFIG))
                .build()),
        aac(
            "AAC in another sample entry",
            audioTrack(48_000, 1_024).sampleEntry(SyntheticSampleEntry.audio("enca", 0)).build()),
        aac(
            "AAC in a sound description of an unknown version",
            audioTrack(48_000, 1_024).sampleEntry(SyntheticSampleEntry.audio("mp4a", 3)).build()),
        AudioCase.builder()
            .condition("E-AC-3 whose samples each hold one block")
            .codecName("eac3")
            .sampleRate(48_000)
            .track(audioTrack(48_000, 256).build())
            .build(),
        AudioCase.builder()
            .condition("E-AC-3 without a sample rate")
            .codecName("eac3")
            .track(audioTrack(48_000, 1_536).build())
            .build());
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
            videoTrack(SyntheticSampleEntry.avc().build())
                .secondTrack(
                    SyntheticMp4.builder()
                        .mediaTimescale(1_000)
                        .sampleDurations(4, 500)
                        .sampleEntry(SyntheticSampleEntry.avc().type("avc3").build())
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

  private static VideoCase h264(String condition, SyntheticSampleEntry.AvcBuilder entry) {
    return VideoCase.builder()
        .condition(condition)
        .codecName("h264")
        .sampleEntry(entry.build())
        .build();
  }

  private static VideoCase hevc(String condition, SyntheticSampleEntry.HevcBuilder entry) {
    return VideoCase.builder()
        .condition(condition)
        .codecName("hevc")
        .sampleEntry(entry.build())
        .build();
  }

  private static AudioCase aac(String condition, SyntheticMp4 track) {
    return AudioCase.builder().condition(condition).codecName("aac").track(track).build();
  }

  /**
   * A video track whose sample entry the copy rule judges under the codec that ffprobe names; a
   * case without a sample entry has a track without an stsd.
   */
  @Builder
  private record VideoCase(
      @NonNull String condition, @NonNull String codecName, byte[] sampleEntry) {
    @Override
    public String toString() {
      return condition;
    }
  }

  /**
   * An audio track beside the video, under the codec and the sample rate that ffprobe names, 0 when
   * the case names none.
   */
  @Builder
  private record AudioCase(
      @NonNull String condition,
      @NonNull String codecName,
      long sampleRate,
      @NonNull SyntheticMp4 track) {

    TrackStream stream() {
      return TrackStream.builder()
          .streamIndex(1)
          .trackId(SyntheticMp4.SECOND_TRACK_ID)
          .isRequired(true)
          .codecName(codecName)
          .sampleRate(sampleRate)
          .build();
    }

    @Override
    public String toString() {
      return condition;
    }
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
    return audioTrack(48_000, 1_024)
        .sampleEntry(sampleEntry)
        .mediaData(firstSampleStartingWith(firstBytes))
        .build();
  }

  /** The media data of four 7-byte samples, the first of which starts with these bytes. */
  private static byte[] firstSampleStartingWith(byte[] firstBytes) {
    var data = new byte[28];
    System.arraycopy(firstBytes, 0, data, 0, firstBytes.length);
    return data;
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
