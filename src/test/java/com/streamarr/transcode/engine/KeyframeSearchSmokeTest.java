package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The search for the keyframe that an encode from a later segment reads from, by real FFmpeg. */
@Tag("SmokeTest")
@DisplayName("Keyframe Search Smoke Tests")
class KeyframeSearchSmokeTest {

  @TempDir Path root;

  // At 4 fps with three B-frames in a pyramid, each picture decodes 0.5 s before it presents. An
  // MPEG-TS seek lands on decode times, so the attempt reads from the keyframe's decode time; an
  // MP4 seek lands on presentation times, so it reads from the keyframe's presentation time, where
  // a seek to its decode time would land on the keyframe before it.
  @ParameterizedTest(name = "{0} from {3} s")
  @CsvSource({"mpegts, ts, 9.5, 18", "mp4, mp4, 10, 18", "mpegts, ts, 7.5, 10", "mp4, mp4, 8, 10"})
  @DisplayName(
      "Should read from where a seek lands on the last keyframe that presents half a second before"
          + " the first segment when an encode searches for it")
  void shouldReadFromWhereASeekLandsOnTheLastKeyframeWhenAnEncodeSearchesForIt(
      String format, String extension, BigDecimal expectedSeconds, int firstSegmentStartSeconds)
      throws Exception {
    var source = delayedPicturesWithKeyframesUpToTenSeconds(format, extension);
    var request = searching(source, OptionalInt.empty(), firstSegmentStartSeconds);

    var readPosition = engine().readPosition(request, _ -> {}).orElseThrow();

    assertThat(readPosition).isEqualTo(secondsOf(expectedSeconds));
  }

  // The video's pictures end at 20 s, before the segment at 24 s, and its last keyframe presents at
  // 10 s, so a held encode reads from the keyframe at 8 s, which gives a deinterlacer the history
  // before the held picture.
  @ParameterizedTest(name = "{0}")
  @CsvSource({"mpegts, ts, 7.5", "mp4, mp4, 8"})
  @DisplayName(
      "Should read from where a seek lands on the keyframe 0.5 s before the video's last when a held"
          + " encode's video ends before its first segment")
  void shouldReadFromTheKeyframeBeforeTheLastWhenAHeldEncodesVideoEndsBeforeItsFirstSegment(
      String format, String extension, BigDecimal expectedSeconds) throws Exception {
    var source = delayedPicturesWithKeyframesUpToTenSeconds(format, extension);

    var readPosition =
        engine().readPosition(searching(source, OptionalInt.empty(), 24), _ -> {}).orElseThrow();

    assertThat(readPosition).isEqualTo(secondsOf(expectedSeconds));
  }

  // The timeline's zero is the earliest start among the streams that a command reads. The 440 Hz
  // tone starts 2 s before the video's first picture, and the 880 Hz tone with the video, 0.02 s
  // before its first picture once the AAC encoder's priming is counted. The video's one keyframe
  // decodes 0.5 s before it presents. On the timeline of an attempt that reads the 440 Hz tone, it
  // presents at 2.021 s and decodes at 1.521 s, and an MPEG-TS seek to its presentation time lands
  // after it, so the attempt reads from its decode time. On the timeline of one that reads the
  // 880 Hz tone, it presents at 0.021 s, where a seek lands on it; a seek to 1.521 s there would
  // land after the keyframe and decode no picture.
  @ParameterizedTest(name = "audio stream {0}")
  @CsvSource({"1, 1.521333333", "2, 0.021333333"})
  @DisplayName(
      "Should read from where the attempt's own seek reads the video's only keyframe when an audio"
          + " stream starts before the video")
  void shouldReadFromWhereTheAttemptsOwnSeekReadsTheOnlyKeyframeWhenAnAudioStreamStartsFirst(
      int audioStream, BigDecimal expectedSeconds) throws Exception {
    var source = tonesThatStartBeforeTheVideo();

    var readPosition =
        engine()
            .readPosition(searching(source, OptionalInt.of(audioStream), 18), _ -> {})
            .orElseThrow();

    assertThat(readPosition)
        .as("where a seek on the attempt's own timeline reads the video's keyframe")
        .isEqualTo(secondsOf(expectedSeconds));
  }

  // A raw H.264 stream carries no index and its demuxer cannot seek: a scan from a seek reads no
  // packet, and the scan from zero finds the keyframes. A seek to the keyframe at 8 s would read no
  // picture.
  @Test
  @DisplayName(
      "Should read from the start of the timeline when the demuxer of a raw H.264 stream cannot seek"
          + " to the keyframe that the search finds")
  void shouldReadFromTheStartOfTheTimelineWhenARawStreamsDemuxerCannotSeekToTheFoundKeyframe()
      throws Exception {
    var source = delayedPicturesWithKeyframesUpToTenSeconds("h264", "h264");

    var readPosition =
        engine().readPosition(searching(source, OptionalInt.empty(), 24), _ -> {}).orElseThrow();

    assertThat(readPosition).isZero();
  }

  // The clip's edit list starts it 1 s into its only GOP, so its keyframe and every picture before
  // the clip's start decode but are not presented.
  @Test
  @DisplayName(
      "Should read from the start of the timeline when the only keyframe of an MP4 clip, which its"
          + " edit list trims inside its GOP, decodes but is not presented")
  void shouldReadFromTheStartOfTheTimelineWhenAnEditListClipsOnlyKeyframeIsNotPresented()
      throws Exception {
    var source = clipTrimmedInsideItsOnlyGop();

    var readPosition =
        engine().readPosition(searching(source, OptionalInt.empty(), 8), _ -> {}).orElseThrow();

    assertThat(readPosition).isZero();
  }

  // The source's keyframes lie 60 s apart, so a read from 5.005 s lands inside the GOP from 0 s,
  // where the decoder shows nothing until the keyframe at 60 s. The check of a read up to 5.5 s
  // sends q once its copies reach 6.5 s, and FFmpeg acts on q within about 100 ms. At this size,
  // FFmpeg reads and decodes on to the keyframe in about 430 ms on the workstation, four times as
  // long as the stop takes.
  @Test
  @DisplayName(
      "Should stop the check a second past the latest picture's time, and count the packets that it"
          + " copied, when its read lands inside a GOP whose next keyframe lies a minute later")
  void shouldStopTheCheckASecondPastTheLatestPictureWhenItsReadLandsInsideAMinuteLongGop()
      throws Exception {
    var source = keyframesAMinuteApart();
    var bytesRead = new AtomicLong();
    var scan =
        KeyframeScan.builder()
            .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
            .launcher(new ProcessBuilderLauncher())
            .request(searching(source, OptionalInt.empty(), 6))
            .bytesRead(bytesRead::addAndGet)
            .build();

    var read = scan.firstDecodedPicture(Duration.ofMillis(5005), Duration.ofMillis(5500));

    assertThat(read)
        .as("no picture: the check stopped before the keyframe at 60 s")
        .isEqualTo(new KeyframeScan.SeekRead.NoPictureInTime());
    assertThat(bytesRead).as("the bytes of the packets that the check copied").hasPositiveValue();
  }

  // The video ends near 6 s and the audio runs on to 120 s. A read from 4.5 s lands after the
  // video's last keyframe, at 4 s, so its decoder shows no picture, and no video packet decodes at
  // its read end, 6.5 s. The packet that presents last is followed by B-frames that present before
  // it, so FFmpeg's MPEG-TS demuxer passes it on at once rather than at the end of the file.
  @Test
  @DisplayName(
      "Should send q once the copied video packet that ends the video is read when the check's"
          + " video ends before its read end")
  void shouldSendQOnceTheCopiedVideoPacketThatEndsTheVideoIsReadWhenTheVideoEndsFirst()
      throws Exception {
    var source = videoThatEndsLongBeforeItsAudio();
    var launcher = new InputRecordingLauncher();
    var scan = firstPictureCheck(source, probe(source).videoEnd(), launcher);

    var read = scan.firstDecodedPicture(Duration.ofMillis(4500), Duration.ofMillis(5500));

    assertThat(read).isEqualTo(new KeyframeScan.SeekRead.NoPictureInTime());
    assertThat(launcher.input()).as("the q that stops the check at the video's end").isEqualTo("q");
  }

  // The MP4's AAC starts 2 ms in, 24.576 of the video's ticks of 1/12288 s. FFmpeg's
  // -start_at_zero rounds that start to 25 ticks, so on the check's timeline the video ends 34.5 µs
  // before the server's video end, which subtracts 2 ms. A read from 5.99 s lands on the video's
  // last packet, its keyframe at 5.98 s, whose picture the decoder holds until FFmpeg flushes it,
  // on q or at the end of the file.
  @Test
  @DisplayName(
      "Should send q once the copied video packet that ends the video is read when the container's"
          + " start lies between two of the video's ticks")
  void shouldSendQAtTheVideosEndWhenTheContainersStartLiesBetweenTwoOfTheVideosTicks()
      throws Exception {
    var source = videoWhoseTimelineStartsBetweenTwoOfItsTicks();
    var probed = probe(source);
    assertThat(probed.start()).as("the container's start").isEqualByComparingTo("0.002");
    assertThat(probed.timeBases()).as("the video's time base").containsEntry(0, "1/12288");
    var launcher = new InputRecordingLauncher();
    var scan = firstPictureCheck(source, probed.videoEnd(), launcher);

    var read = scan.firstDecodedPicture(Duration.ofMillis(5990), Duration.ofMillis(5990));

    assertThat(read).isInstanceOf(KeyframeScan.SeekRead.FirstPicture.class);
    assertThat(launcher.input()).as("the q that stops the check at the video's end").isEqualTo("q");
  }

  // The video's last keyframe presents last, from 4 s up to the video's end at 4.25 s, and decodes
  // at 2.75 s. Its leading pictures follow it in decode order and present from 3.25 s. A read from
  // 4 s lands on the keyframe, so the decoder shows a picture by 3.5 s only from those pictures.
  @Test
  @DisplayName(
      "Should send no q at the keyframe that ends the video when its leading pictures can present"
          + " by the latest picture's time")
  void shouldSendNoQAtTheKeyframeThatEndsTheVideoWhenItsLeadingPicturesCanBeInTime()
      throws Exception {
    var source = videoWhoseLastKeyframeIsFollowedByPicturesThatPresentBeforeIt();
    var probed = probe(source);
    assertThat(probed.videoEnd()).as("the video's end").isEqualTo(Duration.ofMillis(4250));
    var launcher = new InputRecordingLauncher();
    var scan = firstPictureCheck(source, probed.videoEnd(), launcher);

    var read = scan.firstDecodedPicture(Duration.ofSeconds(4), Duration.ofMillis(3500));

    assertThat(read).isEqualTo(new KeyframeScan.SeekRead.FirstPicture(Duration.ofMillis(3250)));
    assertThat(launcher.input()).as("no q before the leading pictures").isEmpty();
  }

  // The pinned FFmpeg writes this source's video from about 4.1 s on after its audio at 110 s. A
  // read from the keyframe at 4 s copies that keyframe, then the audio, then the rest of the video.
  // A check that stopped on copied audio 10 s past its read end rejected that read, whose decoder
  // shows the keyframe's picture once it has the packets after it.
  @Test
  @DisplayName(
      "Should keep every keyframe's stored seek position when the video is written far behind its"
          + " audio and the server sends the video's end")
  void shouldKeepEveryKeyframesStoredPositionWhenTheVideoIsWrittenFarBehindItsAudio()
      throws Exception {
    var source = videoWrittenFarBehindItsAudio();
    var probed = probe(source);
    var request = searching(source, OptionalInt.of(1), 24);
    var storedPositions =
        probed.keyframeDecodeTimes().stream().filter(Duration::isPositive).toList();

    assertThat(probed.latestAudioWrittenBeforeTheVideosLastPacket())
        .as("the audio that FFmpeg wrote before the video's last packet")
        .isGreaterThanOrEqualTo(Duration.ofSeconds(100));
    assertThat(storedPositions).as("the keyframes after the first").hasSize(6);

    var readPositions = new ArrayList<Duration>();
    for (var stored : storedPositions) {
      readPositions.add(
          engine()
              .readPosition(
                  request.toBuilder()
                      .seekPosition(Optional.of(stored))
                      .videoEnd(Optional.of(probed.videoEnd()))
                      .build(),
                  _ -> {})
              .orElseThrow());
    }

    assertThat(readPositions).isEqualTo(storedPositions);
  }

  // 6 s of H.264 at 128x72 and 24 fps with keyframes 2 s apart and 120 s of AAC, encoded apart and
  // then copied into one MPEG-TS file, which interleaves them by time.
  private Path videoThatEndsLongBeforeItsAudio() throws Exception {
    var video = root.resolve("video.ts");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=24:duration=6",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-g",
            "48",
            "-f",
            "mpegts",
            video.toString()));
    var audio = root.resolve("audio.aac");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "anoisesrc=duration=120:color=pink:sample_rate=48000:amplitude=0.1",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            audio.toString()));
    var source = root.resolve("video-ends-first.ts");
    ffmpeg(
        List.of(
            "-i",
            video.toString(),
            "-i",
            audio.toString(),
            "-map",
            "0:v",
            "-map",
            "1:a",
            "-c",
            "copy",
            "-f",
            "mpegts",
            source.toString()));
    return source;
  }

  // 6 s of H.264 at 128x72 and 24 fps with keyframes at 0, 2 and 4 s and on its last picture, and
  // 30 s of AAC, encoded in one FFmpeg into MP4 with every time 24 ms later.
  private Path videoWhoseTimelineStartsBetweenTwoOfItsTicks() throws Exception {
    var source = root.resolve("start-between-ticks.mp4");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=24:duration=6",
            "-f",
            "lavfi",
            "-i",
            "anoisesrc=duration=30:color=pink:sample_rate=48000:amplitude=0.1",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-g",
            "48",
            "-force_key_frames",
            "0,2,4,5.958",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-output_ts_offset",
            "0.024",
            "-f",
            "mp4",
            source.toString()));
    return source;
  }

  // 4.25 s of HEVC at 128x72 and 4 fps with keyframes at 0 and 4 s, and 30 s of AAC, in MP4. x265
  // codes the three pictures before the last keyframe after it, as RADL pictures that need no
  // picture before it.
  private Path videoWhoseLastKeyframeIsFollowedByPicturesThatPresentBeforeIt() throws Exception {
    var source = root.resolve("leading-pictures.mp4");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=4:duration=4.25",
            "-f",
            "lavfi",
            "-i",
            "anoisesrc=duration=30:color=pink:sample_rate=48000:amplitude=0.1",
            "-c:v",
            "libx265",
            "-x265-params",
            "keyint=16:min-keyint=16:bframes=3:b-adapt=0:open-gop=0:radl=3:scenecut=0"
                + ":log-level=error",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-f",
            "mp4",
            source.toString()));
    return source;
  }

  // 6 s of H.264 at 128x72 and 24 fps with IDR keyframes at 0, 1, 2, 3, 4, 5 and 5.5 s, and 120 s
  // of AAC, encoded in one FFmpeg into MPEG-TS.
  private Path videoWrittenFarBehindItsAudio() throws Exception {
    var source = root.resolve("video-far-behind.ts");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=24:duration=6",
            "-f",
            "lavfi",
            "-i",
            "anoisesrc=duration=120:color=pink:sample_rate=48000:amplitude=0.1",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-forced-idr",
            "1",
            "-force_key_frames",
            "0,1,2,3,4,5,5.5",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-f",
            "mpegts",
            source.toString()));
    return source;
  }

  // The source's packets in the order of the file, as ffprobe lists them.
  private ProbedSource probe(Path source) throws Exception {
    var listing = root.resolve("ffprobe-" + System.nanoTime() + ".txt");
    var process =
        new ProcessBuilder(
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=start_time:stream=index,codec_type,time_base"
                    + ":packet=stream_index,pts,dts,duration,flags",
                "-of",
                "compact",
                source.toString())
            .redirectOutput(listing.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertThat(process.waitFor(1, TimeUnit.MINUTES)).as("ffprobe exits").isTrue();
    assertThat(process.exitValue()).as("ffprobe's exit code").isZero();
    return ProbedSource.of(Files.readAllLines(listing));
  }

  /**
   * A source's packets, each stream's time base and the container's start time, which the
   * timeline's zero subtracts, as the probe's keyframe positions do.
   */
  private record ProbedSource(
      BigDecimal start,
      Map<Integer, String> streamTypes,
      Map<Integer, String> timeBases,
      List<Map<String, String>> packets) {

    // ffprobe's compact lines, such as "packet|stream_index=0|pts=133500|...". A stream inside a
    // program repeats under a "program" line, which the stream's own line already gives.
    static ProbedSource of(List<String> lines) {
      var start = BigDecimal.ZERO;
      var streamTypes = new HashMap<Integer, String>();
      var timeBases = new HashMap<Integer, String>();
      var packets = new ArrayList<Map<String, String>>();
      for (var line : lines.stream().filter(line -> line.contains("|")).toList()) {
        var fields = fieldsOf(line);
        switch (line.substring(0, line.indexOf('|'))) {
          case "format" -> start = new BigDecimal(fields.get("start_time"));
          case "stream" -> {
            var index = Integer.parseInt(fields.get("index"));
            streamTypes.put(index, fields.get("codec_type"));
            timeBases.put(index, fields.get("time_base"));
          }
          case "packet" -> packets.add(fields);
          default -> {}
        }
      }

      return new ProbedSource(start, streamTypes, timeBases, packets);
    }

    private static Map<String, String> fieldsOf(String line) {
      var fields = new HashMap<String, String>();
      for (var field : line.split("\\|")) {
        var pair = field.split("=", 2);
        if (pair.length == 2) {
          fields.put(pair[0], pair[1]);
        }
      }

      return fields;
    }

    /**
     * Where the video ends, as a keyframe packet scan reads it: the latest presentation time plus
     * duration of its packets, a packet without a presentation time counting at its decode time.
     */
    Duration videoEnd() {
      return videoPackets().stream()
          .map(packet -> presentationOrDecodeTimeOf(packet).add(timeOf(packet, "duration")))
          .max(Comparator.naturalOrder())
          .map(this::onTheTimeline)
          .orElseThrow();
    }

    List<Duration> keyframeDecodeTimes() {
      return videoPackets().stream()
          .filter(packet -> packet.get("flags").startsWith("K"))
          .map(packet -> onTheTimeline(timeOf(packet, "dts")))
          .toList();
    }

    // The latest audio time among the packets that the file holds before its last video packet.
    Duration latestAudioWrittenBeforeTheVideosLastPacket() {
      var lastVideoPacket = packets.lastIndexOf(videoPackets().getLast());
      return packets.subList(0, lastVideoPacket).stream()
          .filter(packet -> "audio".equals(typeOf(packet)))
          .map(packet -> onTheTimeline(presentationOrDecodeTimeOf(packet)))
          .max(Comparator.naturalOrder())
          .orElseThrow();
    }

    private List<Map<String, String>> videoPackets() {
      return packets.stream().filter(packet -> "video".equals(typeOf(packet))).toList();
    }

    private String typeOf(Map<String, String> packet) {
      return streamTypes.get(Integer.parseInt(packet.get("stream_index")));
    }

    private BigDecimal presentationOrDecodeTimeOf(Map<String, String> packet) {
      return "N/A".equals(packet.get("pts")) ? timeOf(packet, "dts") : timeOf(packet, "pts");
    }

    // The field's ticks of the packet's time base, in seconds to 1e-15 s.
    private BigDecimal timeOf(Map<String, String> packet, String field) {
      var timeBase = timeBases.get(Integer.parseInt(packet.get("stream_index"))).split("/");
      return new BigDecimal(packet.get(field))
          .multiply(new BigDecimal(timeBase[0]))
          .divide(new BigDecimal(timeBase[1]), 15, RoundingMode.HALF_UP);
    }

    private Duration onTheTimeline(BigDecimal seconds) {
      return Duration.ofNanos(
          seconds
              .subtract(start)
              .movePointRight(9)
              .setScale(0, RoundingMode.HALF_UP)
              .longValueExact());
    }
  }

  // 130 s of H.264 at 640x360 and 24 fps with keyframes 60 s apart, in MPEG-TS.
  private Path keyframesAMinuteApart() throws Exception {
    var source = root.resolve("keyframes-a-minute-apart.ts");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=640x360:rate=24:duration=130",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-x264-params",
            "scenecut=0:keyint=1440:min-keyint=1440",
            "-f",
            "mpegts",
            source.toString()));
    return source;
  }

  // 6 s of H.264 at 24 fps with one keyframe, copied from 1 s into a 20 s MP4 source.
  private Path clipTrimmedInsideItsOnlyGop() throws Exception {
    var whole = root.resolve("whole.mp4");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=24:duration=20",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-x264-params",
            "scenecut=0:keyint=1000:min-keyint=1000",
            whole.toString()));
    var clip = root.resolve("clip.mp4");
    ffmpeg(List.of("-ss", "1", "-i", whole.toString(), "-c", "copy", "-t", "6", clip.toString()));
    return clip;
  }

  // 20 s of H.264 at 128x72 and 4 fps, with one keyframe, starting 2 s after a 440 Hz tone, and an
  // 880 Hz tone that starts with the video, in MPEG-TS.
  private Path tonesThatStartBeforeTheVideo() throws Exception {
    var source = root.resolve("tones-before-video.ts");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:sample_rate=48000:duration=40",
            "-itsoffset",
            "2",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=4:duration=20",
            "-itsoffset",
            "2",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=880:sample_rate=48000:duration=40",
            "-map",
            "1:v",
            "-map",
            "0:a",
            "-map",
            "2:a",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-b_strategy",
            "0",
            "-x264-params",
            "b-pyramid=normal:scenecut=0:keyint=1000:min-keyint=1000",
            "-c:a",
            "aac",
            "-f",
            "mpegts",
            source.toString()));
    return source;
  }

  // 20 s of H.264 at 128x72 and 4 fps with IDR keyframes at 0, 2, 4, 6, 8 and 10 s only.
  private Path delayedPicturesWithKeyframesUpToTenSeconds(String format, String extension)
      throws Exception {
    var source = root.resolve("delayed-pictures." + extension);
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=4:duration=20",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-b_strategy",
            "0",
            "-x264-params",
            "b-pyramid=normal:scenecut=0:keyint=1000:min-keyint=1000",
            "-forced-idr",
            "1",
            "-force_key_frames",
            "0,2,4,6,8,10",
            "-f",
            format,
            source.toString()));
    return source;
  }

  private void ffmpeg(List<String> arguments) throws Exception {
    var command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-loglevel", "error"));
    command.addAll(arguments);
    var errors = root.resolve("ffmpeg-" + System.nanoTime() + ".log");
    var process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(errors.toFile())
            .start();
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("FFmpeg exits").isTrue();
    assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
  }

  private static FfmpegTranscodeEngine engine() {
    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
        .capabilityService(
            new TranscodeCapabilityService("ffmpeg", _ -> new FakeProcess("ffmpeg version 8.1", 0)))
        .launcher(new ProcessBuilderLauncher())
        .encoderStallTimeout(Duration.ofSeconds(30))
        .build();
  }

  // The first picture check of a held encode of the source's first audio stream, with the video's
  // end that the server sends.
  private static KeyframeScan firstPictureCheck(
      Path source, Duration videoEnd, ProcessLauncher launcher) {
    return KeyframeScan.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
        .launcher(launcher)
        .request(
            searching(source, OptionalInt.of(1), 6).toBuilder()
                .videoEnd(Optional.of(videoEnd))
                .build())
        .bytesRead(_ -> {})
        .build();
  }

  // A held encode on a 2 s grid that searches for the last keyframe that presents half a second
  // before its first segment, of the named audio stream, or of none.
  private static TranscodeRequest searching(
      Path source, OptionalInt audioStream, int firstSegmentStartSeconds) {
    var audio = AudioDecision.builder().mode(AudioMode.NONE).build();
    if (audioStream.isPresent()) {
      audio =
          AudioDecision.builder()
              .mode(AudioMode.TRANSCODE)
              .codec("aac")
              .channels(2)
              .bitrate(128_000L)
              .streamIndex(audioStream)
              .padsWithSilenceToAdvertisedEnd(true)
              .build();
    }

    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .attemptId(UUID.randomUUID())
        .sourcePath(source)
        .targetSegmentDuration(2)
        .startSequenceNumber(firstSegmentStartSeconds / 2)
        .mediaSegmentCount(20)
        .outputFrameRate(Optional.of(new OutputFrameRate(4, 1)))
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                .videoCodecFamily("h264")
                .videoDecision(
                    VideoDecisionFixtures.encodeBuilder()
                        .holdsLastFrameToAdvertisedEnd(true)
                        .build())
                .audioDecision(audio)
                .subtitleDecision(SubtitleDecisions.EXCLUDED)
                .build())
        .encoderPreset(EncoderPreset.FASTER)
        .width(128)
        .height(72)
        .bitrate(100_000L)
        .readFromLastKeyframePresentingAtOrBefore(
            Optional.of(Duration.ofSeconds(firstSegmentStartSeconds).minusMillis(500)))
        .advertisedEnd(Optional.of(Duration.ofSeconds(40)))
        .build();
  }

  private static Duration secondsOf(BigDecimal seconds) {
    return Duration.ofNanos(seconds.movePointRight(9).longValueExact());
  }
}
