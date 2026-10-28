package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.IsoBoxes.AUDIO_TRACK_ID;
import static com.streamarr.transcode.engine.IsoBoxes.NON_SYNC_SAMPLE_FLAGS;
import static com.streamarr.transcode.engine.IsoBoxes.SYNC_SAMPLE_FLAGS;
import static com.streamarr.transcode.engine.IsoBoxes.TFHD_DEFAULT_BASE_IS_MOOF;
import static com.streamarr.transcode.engine.IsoBoxes.TFHD_DEFAULT_SAMPLE_DURATION;
import static com.streamarr.transcode.engine.IsoBoxes.TFHD_DEFAULT_SAMPLE_FLAGS;
import static com.streamarr.transcode.engine.IsoBoxes.TFHD_DEFAULT_SAMPLE_SIZE;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_DATA_OFFSET;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_FIRST_SAMPLE_FLAGS;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_SAMPLE_COMPOSITION_TIME_OFFSET;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_SAMPLE_DURATION;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_SAMPLE_SIZE;
import static com.streamarr.transcode.engine.IsoBoxes.VIDEO_TRACK_ID;
import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static com.streamarr.transcode.engine.IsoBoxes.ftyp;
import static com.streamarr.transcode.engine.IsoBoxes.fullBox;
import static com.streamarr.transcode.engine.IsoBoxes.moov;
import static com.streamarr.transcode.engine.IsoBoxes.u32;

import com.streamarr.transcode.engine.IsoBoxes.Track;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes the fragmented MP4 that FFmpeg's mp4 muxer writes of an HEVC copy with {@code
 * delay_moov+default_base_moof+frag_keyframe}: each {@code tfhd} declares a default duration, size
 * and flags, a run of one sample states no size, and a run of several states each sample's duration
 * and size, with the first sample's flags, and every run states composition offsets.
 */
final class HevcCopies {

  static final int VIDEO_SAMPLE_DURATION = 1001;
  static final int AUDIO_SAMPLE_DURATION = 1024;
  static final int AUD = 35;
  static final int TRAIL_R = 1;
  static final int SUFFIX_SEI = 40;

  private HevcCopies() {}

  /** An initialization segment of HEVC video, whose NAL units follow 4-byte lengths, and AAC. */
  static byte[] initializationSegment() {
    return initializationSegment(hevcSampleEntry("hev1", 4));
  }

  static byte[] initializationSegment(byte[] videoSampleEntry) {
    return concat(
        ftyp(),
        moov(
            Track.video().sampleEntry(videoSampleEntry).build(),
            Track.audio().defaultSampleDuration(AUDIO_SAMPLE_DURATION).build()));
  }

  /** A sample entry whose hvcC declares only its NAL unit length size. */
  static byte[] hevcSampleEntry(String type, int lengthSize) {
    var hvcC = new byte[23];
    hvcC[0] = 1;
    hvcC[21] = (byte) (0xFC | (lengthSize - 1));
    return box(type, new byte[78], box("hvcC", hvcC));
  }

  /** A NAL unit of the type after a 4-byte length, whose payload is the given bytes. */
  static byte[] nal(int type, int... payload) {
    return nalWithLength(4, type, payload);
  }

  static byte[] nalWithLength(int lengthSize, int type, int... payload) {
    var unit = new byte[2 + payload.length];
    unit[0] = (byte) (type << 1);
    unit[1] = 1;
    for (var index = 0; index < payload.length; index++) {
      unit[2 + index] = (byte) payload[index];
    }

    var length = new byte[lengthSize];
    for (var index = 0; index < lengthSize; index++) {
      length[index] = (byte) (unit.length >>> (8 * (lengthSize - 1 - index)));
    }

    return concat(length, unit);
  }

  static byte[] aud() {
    return nal(AUD, 0x50);
  }

  /** A slice of the picture with the given number. */
  static byte[] slice(int picture) {
    return nal(TRAIL_R, 0xAF, picture);
  }

  /** The RPU of the picture with the given number: rpu_nal_prefix 25, then that number. */
  static byte[] rpu(int picture) {
    return nal(62, 25, picture);
  }

  static byte[] suffixSei(int picture) {
    return nal(SUFFIX_SEI, 4, picture);
  }

  static byte[] sample(byte[]... nalUnits) {
    return concat(nalUnits);
  }

  /** One fragment as FFmpeg writes it, of these video samples and then these audio samples. */
  static byte[] fragment(CopiedFragment fragment) {
    var trafs = new ArrayList<TrackData>();
    if (!fragment.video.isEmpty()) {
      trafs.add(
          new TrackData(
              VIDEO_TRACK_ID,
              fragment.videoDecodeTime,
              fragment.video,
              true,
              fragment.extraVideoTrafBox));
    }

    if (!fragment.audio.isEmpty()) {
      trafs.add(
          new TrackData(
              AUDIO_TRACK_ID, fragment.audioDecodeTime, fragment.audio, false, new byte[0]));
    }

    var moofBytes = moofOf(trafs, 0).length;
    var moof = moofOf(trafs, moofBytes + 8);
    var data = new ByteArrayOutputStream();
    trafs.forEach(track -> track.samples.forEach(data::writeBytes));
    return concat(moof, box("mdat", data.toByteArray()));
  }

  static CopiedFragment.CopiedFragmentBuilder fragmentBuilder() {
    return new CopiedFragment.CopiedFragmentBuilder();
  }

  private static byte[] moofOf(List<TrackData> trafs, int dataStart) {
    var children = new ByteArrayOutputStream();
    children.writeBytes(fullBox("mfhd", 0, u32(1)));
    var dataOffset = dataStart;
    for (var track : trafs) {
      children.writeBytes(track.traf(dataOffset));
      dataOffset += track.samples.stream().mapToInt(sample -> sample.length).sum();
    }

    return box("moof", children.toByteArray());
  }

  private record TrackData(
      int trackId, long decodeTime, List<byte[]> samples, boolean video, byte[] extraBox) {

    private byte[] traf(int dataOffset) {
      var duration = video ? VIDEO_SAMPLE_DURATION : AUDIO_SAMPLE_DURATION;
      var flags = video ? NON_SYNC_SAMPLE_FLAGS : SYNC_SAMPLE_FLAGS;
      var tfhd =
          fullBox(
              "tfhd",
              TFHD_DEFAULT_BASE_IS_MOOF
                  | TFHD_DEFAULT_SAMPLE_DURATION
                  | TFHD_DEFAULT_SAMPLE_SIZE
                  | TFHD_DEFAULT_SAMPLE_FLAGS,
              u32(trackId),
              u32(duration),
              u32(samples.getFirst().length),
              u32(flags));
      var tfdt = fullBox("tfdt", IsoBoxes.VERSION_1, IsoBoxes.u64(decodeTime));
      return box("traf", tfhd, extraBox, tfdt, trun(dataOffset));
    }

    private byte[] trun(int dataOffset) {
      var entries = new ByteArrayOutputStream();
      if (samples.size() == 1) {
        entries.writeBytes(u32(video ? 2002 : 0));
        return fullBox(
            "trun",
            TRUN_DATA_OFFSET | TRUN_SAMPLE_COMPOSITION_TIME_OFFSET,
            u32(1),
            u32(dataOffset),
            entries.toByteArray());
      }

      for (var index = 0; index < samples.size(); index++) {
        entries.writeBytes(u32(VIDEO_SAMPLE_DURATION + index));
        entries.writeBytes(u32(samples.get(index).length));
        entries.writeBytes(u32(index * 1001L));
      }

      return fullBox(
          "trun",
          TRUN_DATA_OFFSET
              | TRUN_FIRST_SAMPLE_FLAGS
              | TRUN_SAMPLE_DURATION
              | TRUN_SAMPLE_SIZE
              | TRUN_SAMPLE_COMPOSITION_TIME_OFFSET,
          u32(samples.size()),
          u32(dataOffset),
          u32(SYNC_SAMPLE_FLAGS),
          entries.toByteArray());
    }
  }

  /** The samples of one fragment and the decode time of each track's first. */
  static final class CopiedFragment {

    private final List<byte[]> video;
    private final List<byte[]> audio;
    private final long videoDecodeTime;
    private final long audioDecodeTime;
    private final byte[] extraVideoTrafBox;

    private CopiedFragment(CopiedFragmentBuilder builder) {
      this.video = List.copyOf(builder.video);
      this.audio = List.copyOf(builder.audio);
      this.videoDecodeTime = builder.videoDecodeTime;
      this.audioDecodeTime = builder.audioDecodeTime;
      this.extraVideoTrafBox = builder.extraVideoTrafBox;
    }

    static final class CopiedFragmentBuilder {
      private final List<byte[]> video = new ArrayList<>();
      private final List<byte[]> audio = new ArrayList<>();
      private long videoDecodeTime;
      private long audioDecodeTime;
      private byte[] extraVideoTrafBox = new byte[0];

      /** A box that the video traf holds after its tfhd, which FFmpeg does not write. */
      CopiedFragmentBuilder extraVideoTrafBox(byte[] box) {
        extraVideoTrafBox = box;
        return this;
      }

      CopiedFragmentBuilder video(long decodeTime, byte[]... samples) {
        videoDecodeTime = decodeTime;
        video.addAll(Arrays.asList(samples));
        return this;
      }

      CopiedFragmentBuilder audio(long decodeTime, byte[]... samples) {
        audioDecodeTime = decodeTime;
        audio.addAll(Arrays.asList(samples));
        return this;
      }

      byte[] build() {
        return fragment(new CopiedFragment(this));
      }
    }
  }
}
