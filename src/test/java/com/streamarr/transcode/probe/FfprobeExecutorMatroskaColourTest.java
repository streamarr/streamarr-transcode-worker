package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.EbmlElements.AUDIO_TRACK_TYPE;
import static com.streamarr.transcode.probe.EbmlElements.CLUSTER;
import static com.streamarr.transcode.probe.EbmlElements.CLUSTER_TIMESTAMP;
import static com.streamarr.transcode.probe.EbmlElements.CODEC_ID;
import static com.streamarr.transcode.probe.EbmlElements.COLOUR;
import static com.streamarr.transcode.probe.EbmlElements.CUES;
import static com.streamarr.transcode.probe.EbmlElements.HEVC_CODEC_ID;
import static com.streamarr.transcode.probe.EbmlElements.LUMINANCE_MAX;
import static com.streamarr.transcode.probe.EbmlElements.MASTERING_METADATA;
import static com.streamarr.transcode.probe.EbmlElements.MAX_CLL;
import static com.streamarr.transcode.probe.EbmlElements.MAX_FALL;
import static com.streamarr.transcode.probe.EbmlElements.PIXEL_WIDTH;
import static com.streamarr.transcode.probe.EbmlElements.PRIMARY_R_CHROMATICITY_X;
import static com.streamarr.transcode.probe.EbmlElements.RANGE;
import static com.streamarr.transcode.probe.EbmlElements.SEEK;
import static com.streamarr.transcode.probe.EbmlElements.SEEK_HEAD;
import static com.streamarr.transcode.probe.EbmlElements.SEEK_ID;
import static com.streamarr.transcode.probe.EbmlElements.SEEK_POSITION;
import static com.streamarr.transcode.probe.EbmlElements.SEGMENT;
import static com.streamarr.transcode.probe.EbmlElements.TRACKS;
import static com.streamarr.transcode.probe.EbmlElements.TRACK_ENTRY;
import static com.streamarr.transcode.probe.EbmlElements.TRACK_NUMBER;
import static com.streamarr.transcode.probe.EbmlElements.TRACK_TYPE;
import static com.streamarr.transcode.probe.EbmlElements.VIDEO;
import static com.streamarr.transcode.probe.EbmlElements.VIDEO_TRACK_TYPE;
import static com.streamarr.transcode.probe.EbmlElements.VOID;
import static com.streamarr.transcode.probe.EbmlElements.codecId;
import static com.streamarr.transcode.probe.EbmlElements.concat;
import static com.streamarr.transcode.probe.EbmlElements.document;
import static com.streamarr.transcode.probe.EbmlElements.element;
import static com.streamarr.transcode.probe.EbmlElements.elementHeader;
import static com.streamarr.transcode.probe.EbmlElements.header;
import static com.streamarr.transcode.probe.EbmlElements.hevcCodecId;
import static com.streamarr.transcode.probe.EbmlElements.unsigned;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.probe.EbmlElements.TrackEntry;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("FFprobe Executor Matroska Colour Tests")
class FfprobeExecutorMatroskaColourTest {

  private static final String MATROSKA = "matroska,webm";
  private static final String FRAME_MASTERING_DISPLAY =
      """
      {"side_data_type":"Mastering display metadata","red_x":"34000/50000",
       "red_y":"16000/50000","green_x":"13250/50000","green_y":"34500/50000",
       "blue_x":"7500/50000","blue_y":"3000/50000","white_point_x":"15635/50000",
       "white_point_y":"16450/50000","min_luminance":"50/10000",
       "max_luminance":"10000000/10000"}""";
  private static final String FRAME_LIGHT_LEVEL =
      """
      {"side_data_type":"Content light level metadata","max_content":1000,"max_average":400}""";

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should report MaxFALL 0 when the video track's Colour holds only MaxCLL")
  void shouldReportMaxFall0WhenTheVideoTracksColourHoldsOnlyMaxCll() throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(element(MAX_CLL, unsigned(900))));

    assertThat(video.getContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(900)
                .setMaxFrameAverageLightLevel(0)
                .build());
  }

  @Test
  @DisplayName("Should report MaxCLL 0 when the video track's Colour holds only MaxFALL")
  void shouldReportMaxCll0WhenTheVideoTracksColourHoldsOnlyMaxFall() throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(element(MAX_FALL, unsigned(300))));

    assertThat(video.getContentLightLevel())
        .isEqualTo(ContentLightLevel.newBuilder().setMaxFrameAverageLightLevel(300).build());
  }

  @Test
  @DisplayName("Should read the track's light level when a Void element precedes the Segment")
  void shouldReadTheTracksLightLevelWhenAVoidElementPrecedesTheSegment() throws IOException {
    var segment = element(SEGMENT, element(TRACKS, trackEntry(1, element(MAX_CLL, unsigned(900)))));
    var video = probeMatroska(MATROSKA, concat(header(), element(VOID, new byte[4]), segment));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should report the track's light level when the first frame also carries one")
  void shouldReportTheTracksLightLevelWhenTheFirstFrameAlsoCarriesOne() throws IOException {
    var video =
        probeMatroska(
            MATROSKA,
            videoTrack(element(MAX_CLL, unsigned(900)), element(MAX_FALL, unsigned(0))),
            FRAME_LIGHT_LEVEL);

    assertThat(video.getContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(900)
                .setMaxFrameAverageLightLevel(0)
                .build());
  }

  @Test
  @DisplayName("Should read the chosen video track when an audio track comes first")
  void shouldReadTheChosenVideoTrackWhenAnAudioTrackComesFirst() throws IOException {
    var audio = TrackEntry.builder().number(1).type(AUDIO_TRACK_TYPE).build().bytes();
    var video =
        probeMatroska(
            MATROSKA,
            document(element(TRACKS, audio, trackEntry(2, element(MAX_CLL, unsigned(700))))));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(700);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("videoEntriesThatFfmpegSkips")
  @DisplayName("Should read the chosen video track when FFmpeg skips an earlier video TrackEntry")
  void shouldReadTheChosenVideoTrackWhenFfmpegSkipsAnEarlierVideoTrackEntry(
      String description, byte[] skipped) throws IOException {
    // FFmpeg's Matroska demuxer makes no stream of a video track whose CodecID is missing or does
    // not start with V, so ffprobe's first video stream is the track that follows it.
    var video =
        probeMatroska(
            MATROSKA,
            document(element(TRACKS, skipped, trackEntry(2, element(MAX_CLL, unsigned(700))))));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel())
        .as(description)
        .isEqualTo(700);
  }

  static Stream<Arguments> videoEntriesThatFfmpegSkips() {
    var colour = element(VIDEO, element(COLOUR, element(MAX_CLL, unsigned(100))));
    return Stream.of(
        Arguments.of(
            "a video track without CodecID",
            TrackEntry.video().codecId(null).child(colour).build().bytes()),
        Arguments.of(
            "a video track with an empty CodecID",
            TrackEntry.video().codecId("").child(colour).build().bytes()),
        Arguments.of(
            "a video track whose CodecID names an audio codec",
            TrackEntry.video().codecId("A_AAC").child(colour).build().bytes()),
        // FFmpeg's EBML parser keeps the last of an element that the track may hold only once.
        Arguments.of(
            "a video track whose TrackType repeats as audio",
            TrackEntry.video()
                .codecId(null)
                .child(element(TRACK_TYPE, unsigned(AUDIO_TRACK_TYPE)))
                .child(codecId(HEVC_CODEC_ID))
                .child(colour)
                .build()
                .bytes()),
        Arguments.of(
            "a video track whose CodecID repeats as an audio codec",
            TrackEntry.video().child(codecId("A_AAC")).child(colour).build().bytes()));
  }

  @Test
  @DisplayName("Should leave the Colour unread when a CodecID repeat runs past FFmpeg's 16 MiB")
  void shouldLeaveTheColourUnreadWhenACodecIdRepeatRunsPastFfmpegs16MiB() throws IOException {
    // FFmpeg's EBML parser fails at a string longer than 16 MiB and keeps the valid CodecID
    // ahead of it, so ffprobe's first video stream is this track, not the one that follows.
    var oversized = new byte[(16 << 20) + 1];
    Arrays.fill(oversized, (byte) 'A');
    var first =
        TrackEntry.video()
            .child(element(CODEC_ID, oversized))
            .child(element(VIDEO, element(COLOUR, element(MAX_CLL, unsigned(900)))))
            .build()
            .bytes();
    var video =
        probeMatroska(
            MATROSKA,
            document(element(TRACKS, first, trackEntry(2, element(MAX_FALL, unsigned(400))))));

    assertThat(video.getContentLightLevelOmitted()).isTrue();
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  @Test
  @DisplayName(
      "Should leave the Colour unread when a TrackType that follows an oversized CodecID repeat"
          + " names another type")
  void shouldLeaveTheColourUnreadWhenATrackTypeThatFollowsAnOversizedCodecIdRepeatNamesAnotherType()
      throws IOException {
    // FFmpeg's parse of the TrackEntry stops at the oversized CodecID, so the TrackType that
    // follows it never reaches FFmpeg, which keeps this track as its first video stream.
    var oversized = new byte[(16 << 20) + 1];
    Arrays.fill(oversized, (byte) 'A');
    var first =
        TrackEntry.video()
            .child(element(CODEC_ID, oversized))
            .child(element(TRACK_TYPE, unsigned(AUDIO_TRACK_TYPE)))
            .child(element(VIDEO, element(COLOUR, element(MAX_CLL, unsigned(900)))))
            .build()
            .bytes();
    var video =
        probeMatroska(
            MATROSKA,
            document(element(TRACKS, first, trackEntry(2, element(MAX_FALL, unsigned(400))))));

    assertThat(video.getContentLightLevelOmitted()).isTrue();
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  @Test
  @DisplayName(
      "Should leave the Colour unread when a TrackType that follows a malformed TrackNumber names"
          + " another type")
  void shouldLeaveTheColourUnreadWhenATrackTypeThatFollowsAMalformedTrackNumberNamesAnotherType()
      throws IOException {
    // FFmpeg's parse of the TrackEntry stops at the nine-byte TrackNumber, so the TrackType that
    // follows it never reaches FFmpeg, which keeps this track as its first video stream.
    var first =
        TrackEntry.video()
            .child(element(TRACK_NUMBER, new byte[9]))
            .child(element(TRACK_TYPE, unsigned(AUDIO_TRACK_TYPE)))
            .child(element(VIDEO, element(COLOUR, element(MAX_CLL, unsigned(900)))))
            .build()
            .bytes();
    var video =
        probeMatroska(
            MATROSKA,
            document(element(TRACKS, first, trackEntry(2, element(MAX_FALL, unsigned(400))))));

    assertThat(video.getContentLightLevelOmitted()).isTrue();
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  @Test
  @DisplayName(
      "Should leave the Colour unread when a malformed value nested in the track's Video precedes a"
          + " TrackType that names another type")
  void
      shouldLeaveTheColourUnreadWhenAMalformedValueNestedInTheTracksVideoPrecedesATrackTypeThatNamesAnotherType()
          throws IOException {
    // FFmpeg's parse of the TrackEntry stops inside the Video at the nine-byte PixelWidth.
    var first =
        TrackEntry.video()
            .child(
                element(
                    VIDEO,
                    element(COLOUR, element(MAX_CLL, unsigned(900))),
                    element(PIXEL_WIDTH, new byte[9])))
            .child(element(TRACK_TYPE, unsigned(AUDIO_TRACK_TYPE)))
            .build()
            .bytes();
    var video =
        probeMatroska(
            MATROSKA,
            document(element(TRACKS, first, trackEntry(2, element(MAX_FALL, unsigned(400))))));

    assertThat(video.getContentLightLevelOmitted()).isTrue();
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  @Test
  @DisplayName("Should report the last MaxCLL when the Colour repeats it, as FFmpeg does")
  void shouldReportTheLastMaxCllWhenTheColourRepeatsIt() throws IOException {
    var video =
        probeMatroska(
            MATROSKA, videoTrack(element(MAX_CLL, unsigned(100)), element(MAX_CLL, unsigned(900))));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should mark the light level omitted when MaxCLL does not fit its SEI field")
  void shouldMarkTheLightLevelOmittedWhenMaxCllDoesNotFitItsSeiField() throws IOException {
    var video =
        probeMatroska(MATROSKA, videoTrack(element(MAX_CLL, unsigned(65_536))), FRAME_LIGHT_LEVEL);

    assertThat(video.hasContentLightLevel()).isFalse();
    assertThat(video.getContentLightLevelOmitted()).isTrue();
  }

  @Test
  @DisplayName("Should take the first frame's light level when the Colour holds neither element")
  void shouldTakeTheFirstFramesLightLevelWhenTheColourHoldsNeitherElement() throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(element(RANGE, unsigned(1))), FRAME_LIGHT_LEVEL);

    assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(400);
    assertThat(video.getContentLightLevelOmitted()).isFalse();
  }

  @Test
  @DisplayName("Should take the first frame's light level when MaxCLL is longer than 8 bytes")
  void shouldTakeTheFirstFramesLightLevelWhenMaxCllIsLongerThan8Bytes() throws IOException {
    var video =
        probeMatroska(MATROSKA, videoTrack(element(MAX_CLL, new byte[9])), FRAME_LIGHT_LEVEL);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
  }

  @Test
  @DisplayName("Should take the first frame's light level when the file is no Matroska file")
  void shouldTakeTheFirstFramesLightLevelWhenTheFileIsNoMatroskaFile() throws IOException {
    var video = probeMatroska(MATROSKA, new byte[] {0x00, 0x01}, FRAME_LIGHT_LEVEL);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
  }

  @Test
  @DisplayName(
      "Should mark both static values omitted when the probe cannot open the source and read no"
          + " frames")
  void shouldMarkBothStaticValuesOmittedWhenTheProbeCannotOpenTheSourceAndReadNoFrames() {
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(output(MATROSKA, ""))
                    .source(temporaryDirectory.resolve("missing.mkv"))
                    .build())
            .getStreams(0);

    assertThat(video.getFirstFramesRead()).isFalse();
    assertThat(video.hasContentLightLevel()).isFalse();
    assertThat(video.getContentLightLevelOmitted()).isTrue();
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  static Stream<Arguments> unreadableColours() {
    return Stream.of(
        Arguments.of("the file holds no Tracks element", document()),
        Arguments.of(
            "the Tracks element holds no video track",
            document(element(TRACKS, TrackEntry.builder().type(AUDIO_TRACK_TYPE).build().bytes()))),
        // FFmpeg's EBML parser fails at an unsigned integer longer than 8 bytes, though a valid
        // repeat follows.
        Arguments.of(
            "a TrackType longer than 8 bytes ahead of a valid one",
            document(
                element(
                    TRACKS,
                    element(
                        TRACK_ENTRY,
                        element(TRACK_NUMBER, unsigned(1)),
                        element(TRACK_TYPE, new byte[9]),
                        element(TRACK_TYPE, unsigned(VIDEO_TRACK_TYPE)),
                        hevcCodecId(),
                        element(VIDEO, element(COLOUR, element(MAX_CLL, unsigned(900)))))))),
        Arguments.of(
            "a MaxCLL longer than 8 bytes ahead of a valid one",
            videoTrack(element(MAX_CLL, new byte[9]), element(MAX_CLL, unsigned(900)))),
        Arguments.of(
            "a track entry's size runs past the Tracks element",
            document(element(TRACKS, new byte[] {(byte) TRACK_ENTRY, (byte) 0x9F, 0x00}))),
        // The first byte of a two-byte element ID ends the Tracks element.
        Arguments.of(
            "an element ID cut short by the end of the Tracks element",
            document(element(TRACKS, new byte[] {0x42}))),
        // The Segment's data starts 42 bytes into the file, so the Tracks would start before it.
        Arguments.of(
            "a SeekPosition past 2^63 names the Tracks",
            document(seekHead(TRACKS, -1_000_000), cluster(), tracksWithMaxCll(900))),
        Arguments.of(
            "a SeekID longer than 8 bytes ahead of a valid one",
            document(
                element(
                    SEEK_HEAD,
                    element(
                        SEEK,
                        element(SEEK_ID, new byte[9]),
                        element(SEEK_ID, unsigned(TRACKS)),
                        element(SEEK_POSITION, unsigned(0)))),
                tracksWithMaxCll(900))),
        Arguments.of(
            "a Range longer than 8 bytes ahead of MaxCLL",
            videoTrack(element(RANGE, new byte[9]), element(MAX_CLL, unsigned(900)))),
        Arguments.of(
            "a three-byte primary in a MasteringMetadata ahead of MaxCLL",
            videoTrack(
                element(MASTERING_METADATA, element(PRIMARY_R_CHROMATICITY_X, new byte[3])),
                element(MAX_CLL, unsigned(900)))),
        Arguments.of(
            "a MaxCLL longer than 8 bytes in a later Video element's Colour",
            document(
                element(
                    TRACKS,
                    TrackEntry.video()
                        .child(element(VIDEO, element(COLOUR, element(MAX_CLL, unsigned(900)))))
                        .child(element(VIDEO, element(COLOUR, element(MAX_CLL, new byte[9]))))
                        .build()
                        .bytes()))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableColours")
  @DisplayName(
      "Should mark both static values omitted for any codec when the probe cannot read the track's"
          + " Colour")
  void shouldMarkBothStaticValuesOmittedForAnyCodecWhenTheProbeCannotReadTheTracksColour(
      String description, byte[] file) throws IOException {
    var source = Files.write(temporaryDirectory.resolve("source.mkv"), file);
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(
                        """
                        {"streams":[{"index":0,"codec_type":"video","codec_name":"vp9",
                          "time_base":"1/1000"}],"format":{"format_name":"%s"}}
                        """
                            .formatted(MATROSKA))
                    .firstFrames(FirstReads.frames("", "", ""))
                    .source(source)
                    .build())
            .getStreams(0);

    assertThat(video.getContentLightLevelOmitted()).as(description).isTrue();
    assertThat(video.getMasteringDisplayOmitted()).as(description).isTrue();
  }

  @Test
  @DisplayName("Should read no track elements when the container is not Matroska")
  void shouldReadNoTrackElementsWhenTheContainerIsNotMatroska() throws IOException {
    var video =
        probeMatroska("mov,mp4,m4a,3gp,3g2,mj2", videoTrack(element(MAX_CLL, unsigned(900))));

    assertThat(video.hasContentLightLevel()).isFalse();
  }

  @Test
  @DisplayName("Should keep ffprobe's light level when ffprobe reports one for the stream")
  void shouldKeepFfprobesLightLevelWhenFfprobeReportsOneForTheStream() throws IOException {
    var source =
        Files.write(
            temporaryDirectory.resolve("source.mkv"),
            videoTrack(element(MAX_CLL, unsigned(900)), element(MAX_FALL, unsigned(100))));
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(
                        output(
                            MATROSKA,
                            """
                            ,"side_data_list":[{"side_data_type":"Content light level metadata",
                              "max_content":950,"max_average":120}]"""))
                    .source(source)
                    .build())
            .getStreams(0);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(950);
  }

  @Test
  @DisplayName(
      "Should mark the mastering display omitted when ffprobe drops the track's incomplete"
          + " MasteringMetadata")
  void shouldMarkTheMasteringDisplayOmittedWhenFfprobeDropsTheTracksIncompleteMasteringMetadata()
      throws IOException {
    // FFmpeg's Matroska demuxer drops a MasteringMetadata element without all of its primaries.
    var video = probeMatroska(MATROSKA, videoTrack(maxLuminanceOnly()));

    assertThat(video.hasMasteringDisplay()).isFalse();
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
    assertThat(video.getContentLightLevelOmitted()).as("the Colour was read").isFalse();
  }

  @Test
  @DisplayName("Should take the first frame's mastering display when the track's is incomplete")
  void shouldTakeTheFirstFramesMasteringDisplayWhenTheTracksIsIncomplete() throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(maxLuminanceOnly()), FRAME_MASTERING_DISPLAY);

    assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
    assertThat(video.getMasteringDisplayOmitted()).isFalse();
  }

  @Test
  @DisplayName("Should keep ffprobe's mastering display when ffprobe reports one for the stream")
  void shouldKeepFfprobesMasteringDisplayWhenFfprobeReportsOneForTheStream() throws IOException {
    var source =
        Files.write(temporaryDirectory.resolve("source.mkv"), videoTrack(maxLuminanceOnly()));
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(
                        output(MATROSKA, ",\"side_data_list\":[" + FRAME_MASTERING_DISPLAY + "]"))
                    .source(source)
                    .build())
            .getStreams(0);

    assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
    assertThat(video.getMasteringDisplayOmitted()).isFalse();
  }

  @Test
  @DisplayName(
      "Should leave the mastering display unmarked when the track holds no MasteringMetadata")
  void shouldLeaveTheMasteringDisplayUnmarkedWhenTheTrackHoldsNoMasteringMetadata()
      throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(element(MAX_CLL, unsigned(900))));

    assertThat(video.getMasteringDisplayOmitted()).isFalse();
  }

  @Test
  @DisplayName(
      "Should read the track's Colour when the file ends inside the Cues that its SeekHead names")
  void shouldReadTheTracksColourWhenTheFileEndsInsideTheCuesThatItsSeekHeadNames()
      throws IOException {
    // A partly copied file: the Cues come after the Clusters, and the copy stopped inside them.
    var tracks =
        element(TRACKS, trackEntry(1, element(MAX_CLL, unsigned(900)), maxLuminanceOnly()));
    var cluster = element(CLUSTER, element(CLUSTER_TIMESTAMP, unsigned(0)));
    var cuesPosition = seekHead(CUES, 0).length + tracks.length + cluster.length;
    var whole =
        document(seekHead(CUES, cuesPosition), tracks, cluster, element(CUES, new byte[64]));

    var video = probeMatroska(MATROSKA, Arrays.copyOf(whole, whole.length - 32));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  @Test
  @DisplayName("Should read the track's Colour when a malformed SeekHead follows the Tracks")
  void shouldReadTheTracksColourWhenAMalformedSeekHeadFollowsTheTracks() throws IOException {
    var tracks =
        element(TRACKS, trackEntry(1, element(MAX_CLL, unsigned(900)), maxLuminanceOnly()));
    // A zero byte starts no EBML element ID.
    var malformedSeekHead = element(SEEK_HEAD, new byte[] {0x00, 0x00});

    var video = probeMatroska(MATROSKA, document(tracks, malformedSeekHead));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  @Test
  @DisplayName("Should leave the mastering display unmarked when the container is not Matroska")
  void shouldLeaveTheMasteringDisplayUnmarkedWhenTheContainerIsNotMatroska() throws IOException {
    var video = probeMatroska("mov,mp4,m4a,3gp,3g2,mj2", videoTrack(maxLuminanceOnly()));

    assertThat(video.getMasteringDisplayOmitted()).isFalse();
  }

  @Test
  @DisplayName(
      "Should mark both static values omitted when the probe cannot read the track's Colour")
  void shouldMarkBothStaticValuesOmittedWhenTheProbeCannotReadTheTracksColour() throws IOException {
    // The Colour can hold a MasteringMetadata element that FFmpeg drops, so a stream whose packets
    // rule out every SEI message may still carry static HDR metadata.
    var video = probeWalkedMatroska(new byte[] {0x00, 0x01});

    assertThat(video.getMasteringDisplayOmitted()).isTrue();
    assertThat(video.getContentLightLevelOmitted()).isTrue();
  }

  @Test
  @DisplayName(
      "Should leave both static values unflagged when the track's Colour and the packets hold"
          + " neither")
  void shouldLeaveBothStaticValuesUnflaggedWhenTheTracksColourAndThePacketsHoldNeither()
      throws IOException {
    var video = probeWalkedMatroska(videoTrack());

    assertThat(video.getMasteringDisplayOmitted()).isFalse();
    assertThat(video.getContentLightLevelOmitted()).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tracksWithoutAColour")
  @DisplayName("Should leave both static values unflagged when the track holds no Colour")
  void shouldLeaveBothStaticValuesUnflaggedWhenTheTrackHoldsNoColour(
      String description, byte[] track) throws IOException {
    var video = probeWalkedMatroska(document(element(TRACKS, track)));

    assertThat(video.getMasteringDisplayOmitted()).as(description).isFalse();
    assertThat(video.getContentLightLevelOmitted()).as(description).isFalse();
  }

  static Stream<Arguments> tracksWithoutAColour() {
    return Stream.of(
        Arguments.of("a track entry without a Video element", TrackEntry.video().build().bytes()),
        Arguments.of(
            "a Video element without a Colour element",
            TrackEntry.video()
                .child(element(VIDEO, element(PIXEL_WIDTH, unsigned(64))))
                .build()
                .bytes()));
  }

  @Test
  @DisplayName("Should report no video stream when a Matroska source holds only audio")
  void shouldReportNoVideoStreamWhenAMatroskaSourceHoldsOnlyAudio() {
    var result =
        RecordedFfprobe.probeResult(
            """
            {"streams":[{"index":0,"codec_type":"audio","codec_name":"opus"}],
             "format":{"format_name":"%s"}}
            """
                .formatted(MATROSKA));

    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM);
  }

  @Test
  @DisplayName(
      "Should mark the mastering display omitted when ffprobe reports only the stream's light level")
  void shouldMarkTheMasteringDisplayOmittedWhenFfprobeReportsOnlyTheStreamsLightLevel()
      throws IOException {
    var video = probeWithFfprobeSideData(videoTrack(maxLuminanceOnly()), FRAME_LIGHT_LEVEL);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  @Test
  @DisplayName(
      "Should read the track's light level when ffprobe reports only the stream's mastering"
          + " display")
  void shouldReadTheTracksLightLevelWhenFfprobeReportsOnlyTheStreamsMasteringDisplay()
      throws IOException {
    var video =
        probeWithFfprobeSideData(
            videoTrack(element(MAX_CLL, unsigned(900))), FRAME_MASTERING_DISPLAY);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should read the chosen track's Colour when a malformed TrackEntry follows it")
  void shouldReadTheChosenTracksColourWhenAMalformedTrackEntryFollowsIt() throws IOException {
    // FFmpeg's parse of the Tracks stops at the nine-byte TrackNumber, after the chosen track.
    var malformed =
        TrackEntry.video().number(2).child(element(TRACK_NUMBER, new byte[9])).build().bytes();
    var video =
        probeMatroska(
            MATROSKA,
            document(element(TRACKS, trackEntry(1, element(MAX_CLL, unsigned(900))), malformed)));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tracksWithTwoColours")
  @DisplayName("Should read the first Colour when the track holds two, as FFmpeg does")
  void shouldReadTheFirstColourWhenTheTrackHoldsTwo(String description, byte[] track)
      throws IOException {
    var video = probeMatroska(MATROSKA, document(element(TRACKS, track)));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel())
        .as(description)
        .isEqualTo(900);
  }

  static Stream<Arguments> tracksWithTwoColours() {
    var first = element(COLOUR, element(MAX_CLL, unsigned(900)));
    var second = element(COLOUR, element(MAX_CLL, unsigned(100)));
    return Stream.of(
        Arguments.of(
            "one Video element holds both",
            TrackEntry.video().child(element(VIDEO, first, second)).build().bytes()),
        Arguments.of(
            "each of two Video elements holds one",
            TrackEntry.video()
                .child(element(VIDEO, first))
                .child(element(VIDEO, second))
                .build()
                .bytes()));
  }

  @Test
  @DisplayName("Should seek to the Tracks when a Seek repeats its SeekID, as FFmpeg does")
  void shouldSeekToTheTracksWhenASeekRepeatsItsSeekId() throws IOException {
    // FFmpeg keeps the last SeekID of a Seek, so this Seek names the Tracks, not the Cues.
    var seekHeadLength =
        element(SEEK_HEAD, seek(element(SEEK_ID, unsigned(CUES)), TRACKS, 0)).length;
    var tracksPosition = seekHeadLength + cluster().length;
    var seekHead =
        element(SEEK_HEAD, seek(element(SEEK_ID, unsigned(CUES)), TRACKS, tracksPosition));

    var video = probeMatroska(MATROSKA, document(seekHead, cluster(), tracksWithMaxCll(900)));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should read a CodecID of exactly FFmpeg's 16 MiB when it names a video codec")
  void shouldReadACodecIdOfExactlyFfmpegs16MiBWhenItNamesAVideoCodec() throws IOException {
    var codec = new byte[16 << 20];
    Arrays.fill(codec, (byte) 'V');
    var first =
        TrackEntry.video()
            .codecId(null)
            .child(element(CODEC_ID, codec))
            .child(element(VIDEO, element(COLOUR, element(MAX_CLL, unsigned(900)))))
            .build()
            .bytes();
    var video =
        probeMatroska(
            MATROSKA,
            document(element(TRACKS, first, trackEntry(2, element(MAX_FALL, unsigned(400))))));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should report a MaxCLL of 65535 when it fits its SEI field")
  void shouldReportAMaxCllOf65535WhenItFitsItsSeiField() throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(element(MAX_CLL, unsigned(65_535))));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(65_535);
    assertThat(video.getContentLightLevelOmitted()).isFalse();
  }

  /**
   * An HEVC stream in Matroska whose hvcC record and 3 first packets the SEI walk reads in full,
   * and whose 3 first frames carry no side data.
   */
  private ProbeStreamInfo probeWalkedMatroska(byte[] file) throws IOException {
    var source = Files.write(temporaryDirectory.resolve("source.mkv"), file);
    var hvccRecord = "01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 0f 00";
    return probe(
            Answers.builder()
                .ffprobeOutput(output(MATROSKA, "," + FirstReads.extradata(hvccRecord)))
                .firstFrames(FirstReads.frames("", "", ""))
                .firstPackets(
                    FirstReads.packetsUpTo(
                        3,
                        FirstReads.lengthPrefixed("02 01 d0"),
                        FirstReads.lengthPrefixed("4e 01 05 01 00 80", "26 01 af")))
                .source(source)
                .build())
        .getStreams(0);
  }

  @Test
  @Timeout(120)
  @DisplayName(
      "Should read the Colour within a 96 MiB heap when it holds 4 million MasteringMetadata"
          + " elements")
  void shouldReadTheColourWithinA96MibHeapWhenItHolds4MillionMasteringMetadataElements()
      throws IOException, InterruptedException {
    // 40 MB of empty MasteringMetadata elements, which a lookup that held every match before
    // taking the first could not hold in 96 MiB.
    var source = temporaryDirectory.resolve("source.mkv");
    writeColourOfEmptyMasteringMetadata(source, 4_000_000);
    var result = temporaryDirectory.resolve("result.txt");
    var java = Path.of(System.getProperty("java.home"), "bin", "java");
    var probe =
        new ProcessBuilder(
                java.toString(),
                "-Xmx96m",
                "-cp",
                System.getProperty("java.class.path"),
                MatroskaColourBoundedHeapProbe.class.getName(),
                source.toString(),
                result.toString())
            .redirectErrorStream(true)
            .start();

    var output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(probe.waitFor()).as(output).isZero();
    assertThat(Files.readString(result))
        .isEqualTo("mastering_display_omitted=true content_light_level_omitted=false");
  }

  /**
   * Writes a Matroska file whose one video track's Colour holds {@code count} empty
   * MasteringMetadata elements, one at a time, so that the test never holds the whole file.
   */
  private static void writeColourOfEmptyMasteringMetadata(Path file, int count) throws IOException {
    var empty = element(MASTERING_METADATA);
    var colourSize = (long) empty.length * count;
    var videoSize = elementHeader(COLOUR, colourSize).length + colourSize;
    var entryPrefix =
        concat(
            element(TRACK_NUMBER, unsigned(1)),
            element(TRACK_TYPE, unsigned(VIDEO_TRACK_TYPE)),
            hevcCodecId());
    var entrySize = entryPrefix.length + elementHeader(VIDEO, videoSize).length + videoSize;
    var tracksSize = elementHeader(TRACK_ENTRY, entrySize).length + entrySize;
    var segmentSize = elementHeader(TRACKS, tracksSize).length + tracksSize;
    try (var output = new BufferedOutputStream(Files.newOutputStream(file))) {
      output.write(
          concat(
              header(),
              elementHeader(SEGMENT, segmentSize),
              elementHeader(TRACKS, tracksSize),
              elementHeader(TRACK_ENTRY, entrySize),
              entryPrefix,
              elementHeader(VIDEO, videoSize),
              elementHeader(COLOUR, colourSize)));
      for (var written = 0; written < count; written++) {
        output.write(empty);
      }
    }
  }

  @Test
  @DisplayName(
      "Should mark the mastering display omitted when a later Video element holds the track's"
          + " Colour")
  void shouldMarkTheMasteringDisplayOmittedWhenALaterVideoElementHoldsTheTracksColour()
      throws IOException {
    // FFmpeg's Matroska demuxer reads every Video element of a track into one, and takes the first
    // Colour of any of them.
    var video = probeMatroska(MATROSKA, videoTrackWithColourInSecondVideo(maxLuminanceOnly()));

    assertThat(video.hasMasteringDisplay()).isFalse();
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
    assertThat(video.getContentLightLevelOmitted()).as("the Colour was read").isFalse();
  }

  @Test
  @DisplayName("Should read the light level when a later Video element holds the Colour")
  void shouldReadTheLightLevelWhenALaterVideoElementHoldsTheColour() throws IOException {
    var video =
        probeMatroska(MATROSKA, videoTrackWithColourInSecondVideo(element(MAX_CLL, unsigned(900))));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
  }

  /** A Matroska file whose video track holds a Video element without Colour, then one with it. */
  private static byte[] videoTrackWithColourInSecondVideo(byte[]... colour) {
    var entry =
        TrackEntry.video()
            .child(element(VIDEO, element(PIXEL_WIDTH, unsigned(64))))
            .child(element(VIDEO, element(COLOUR, colour)))
            .build()
            .bytes();
    return document(element(TRACKS, entry));
  }

  /** A MasteringMetadata element that holds only LuminanceMax, 1000 cd/m^2 as a 4-byte float. */
  private static byte[] maxLuminanceOnly() {
    return element(
        MASTERING_METADATA, element(LUMINANCE_MAX, ByteBuffer.allocate(4).putFloat(1000).array()));
  }

  /** A SeekHead that names one element's position in the Segment, as an 8-byte number. */
  private static byte[] seekHead(long id, long position) {
    return element(SEEK_HEAD, seek(new byte[0], id, position));
  }

  /** A Seek whose {@code leading} children precede its SeekID and 8-byte SeekPosition. */
  private static byte[] seek(byte[] leading, long id, long position) {
    return element(
        SEEK,
        leading,
        element(SEEK_ID, unsigned(id)),
        element(SEEK_POSITION, ByteBuffer.allocate(8).putLong(position).array()));
  }

  private static byte[] cluster() {
    return element(CLUSTER, element(CLUSTER_TIMESTAMP, unsigned(0)));
  }

  private static byte[] tracksWithMaxCll(long maxCll) {
    return element(TRACKS, trackEntry(1, element(MAX_CLL, unsigned(maxCll))));
  }

  /** Probes a Matroska file for which ffprobe reports this side data of the video stream. */
  private ProbeStreamInfo probeWithFfprobeSideData(byte[] file, String sideData)
      throws IOException {
    var source = Files.write(temporaryDirectory.resolve("source.mkv"), file);
    return probe(
            Answers.builder()
                .ffprobeOutput(output(MATROSKA, ",\"side_data_list\":[" + sideData + "]"))
                .source(source)
                .build())
        .getStreams(0);
  }

  private ProbeStreamInfo probeMatroska(String formatName, byte[] file) throws IOException {
    return probeMatroska(formatName, file, null);
  }

  private ProbeStreamInfo probeMatroska(String formatName, byte[] file, String frameSideData)
      throws IOException {
    var source = Files.write(temporaryDirectory.resolve("source.mkv"), file);
    var answers = Answers.builder().ffprobeOutput(output(formatName, "")).source(source);
    if (frameSideData != null) {
      answers.firstFrames(
          "{\"frames\":[{\"stream_index\":0,\"side_data_list\":[" + frameSideData + "]}]}");
    }

    return probe(answers.build()).getStreams(0);
  }

  /** A Matroska file whose one track is a video track with these Colour children. */
  private static byte[] videoTrack(byte[]... colour) {
    return document(element(TRACKS, trackEntry(1, colour)));
  }

  /** A video track whose Video element holds a PixelWidth, then a Colour with these children. */
  private static byte[] trackEntry(long number, byte[]... colour) {
    return TrackEntry.video()
        .number(number)
        .child(element(VIDEO, element(PIXEL_WIDTH, unsigned(64)), element(COLOUR, colour)))
        .build()
        .bytes();
  }

  private static String output(String formatName, String fields) {
    return """
        {"streams":[{"index":0,"codec_type":"video","codec_name":"hevc","time_base":"1/1000"%s}],
         "format":{"format_name":"%s"}}
        """
        .formatted(fields, formatName);
  }
}
