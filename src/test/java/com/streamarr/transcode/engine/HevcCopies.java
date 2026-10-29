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
import java.util.OptionalInt;

/**
 * Writes the fragmented MP4 that FFmpeg's mp4 muxer writes of an HEVC copy with {@code
 * delay_moov+default_base_moof+frag_keyframe}: each {@code tfhd} declares a default duration, size
 * and flags, and the defaults make every video sample a non-sync sample. A fragment that starts
 * with a keyframe gives its first video sample sync flags of its own. A video run of one sample
 * states no size, and one of several states each sample's duration and size; every video run states
 * composition offsets. An audio run keeps the default duration and states each sample's size when
 * it holds several.
 */
final class HevcCopies {

  static final int VIDEO_SAMPLE_DURATION = 1001;
  static final int AUDIO_SAMPLE_DURATION = 1024;
  static final int AUD = 35;
  static final int TRAIL_R = 1;
  static final int VPS = 32;
  static final int SPS = 33;
  static final int PPS = 34;
  static final int END_OF_SEQUENCE = 36;
  static final int SUFFIX_SEI = 40;
  static final int UNSPECIFIED_62 = 62;
  static final int RPU_NAL_PREFIX = 25;

  // The composition offset of a video run of one sample, as a stream with B-frames gives it.
  private static final int LONE_VIDEO_SAMPLE_COMPOSITION_OFFSET = 2002;
  private static final int DEFAULT_LENGTH_SIZE = 4;
  private static final int SLICE_SEGMENT_HEADER = 0xAF;
  private static final int AUD_PICTURE_TYPE = 0x50;
  private static final int SEI_PAYLOAD_TYPE = 4;

  private HevcCopies() {}

  /** An initialization segment of HEVC video, whose NAL units follow 4-byte lengths, and AAC. */
  static byte[] initializationSegment() {
    return initializationSegment(hevcSampleEntry("hev1", DEFAULT_LENGTH_SIZE));
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

  /** The RPU of a picture: rpu_nal_prefix 25, then the picture's number. */
  static NalUnitBuilder rpuUnit() {
    return new NalUnitBuilder(UNSPECIFIED_62, OptionalInt.of(RPU_NAL_PREFIX));
  }

  /** A slice of a picture: its slice segment header's first byte, then the picture's number. */
  static NalUnitBuilder sliceUnit() {
    return new NalUnitBuilder(TRAIL_R, OptionalInt.of(SLICE_SEGMENT_HEADER));
  }

  static NalUnitBuilder audUnit() {
    return new NalUnitBuilder(AUD, OptionalInt.of(AUD_PICTURE_TYPE));
  }

  /** An end of sequence NAL unit: its 2-byte header and no payload. */
  static NalUnitBuilder endOfSequenceUnit() {
    return new NalUnitBuilder(END_OF_SEQUENCE, OptionalInt.empty());
  }

  static byte[] aud() {
    return audUnit().build();
  }

  /** A slice of the picture with the given number. */
  static byte[] slice(int picture) {
    return sliceUnit().picture(picture).build();
  }

  /** The RPU of the picture with the given number. */
  static byte[] rpu(int picture) {
    return rpuUnit().picture(picture).build();
  }

  static byte[] suffixSei(int picture) {
    return new NalUnitBuilder(SUFFIX_SEI, OptionalInt.of(SEI_PAYLOAD_TYPE))
        .picture(picture)
        .build();
  }

  /** A parameter set of the given type, which no picture's number distinguishes. */
  static byte[] parameterSet(int type) {
    return new NalUnitBuilder(type, OptionalInt.of(0x01)).build();
  }

  static byte[] sample(byte[]... nalUnits) {
    return concat(nalUnits);
  }

  static CopiedFragment.CopiedFragmentBuilder fragmentBuilder() {
    return new CopiedFragment.CopiedFragmentBuilder();
  }

  /** One fragment as FFmpeg writes it, of its video samples and then its audio samples. */
  private static byte[] fragment(CopiedFragment fragment) {
    var moofBytes = moofOf(fragment, 0).length;
    var moof = moofOf(fragment, moofBytes + 8);
    var data = new ByteArrayOutputStream();
    fragment.video.forEach(data::writeBytes);
    fragment.audio.forEach(data::writeBytes);
    return concat(moof, box("mdat", data.toByteArray()));
  }

  private static byte[] moofOf(CopiedFragment fragment, int dataStart) {
    var children = new ByteArrayOutputStream();
    children.writeBytes(fullBox("mfhd", 0, u32(1)));
    children.writeBytes(fragment.extraMoofBox);
    var dataOffset = dataStart;
    if (!fragment.video.isEmpty()) {
      children.writeBytes(videoTraf(fragment, dataOffset));
      dataOffset += bytesOf(fragment.video);
    }

    if (!fragment.audio.isEmpty()) {
      children.writeBytes(audioTraf(fragment, dataOffset));
    }

    return box("moof", children.toByteArray());
  }

  private static byte[] videoTraf(CopiedFragment fragment, int dataOffset) {
    var samples = fragment.video;
    var tfhd = tfhdOf(VIDEO_TRACK_ID, VIDEO_SAMPLE_DURATION, samples, NON_SYNC_SAMPLE_FLAGS);
    var tfdt = fullBox("tfdt", IsoBoxes.VERSION_1, IsoBoxes.u64(fragment.videoDecodeTime));
    var firstSampleFlags = fragment.startsWithKeyframe ? TRUN_FIRST_SAMPLE_FLAGS : 0;
    var keyframeFlags = fragment.startsWithKeyframe ? u32(SYNC_SAMPLE_FLAGS) : new byte[0];
    var entries = new ByteArrayOutputStream();
    if (samples.size() == 1) {
      entries.writeBytes(u32(LONE_VIDEO_SAMPLE_COMPOSITION_OFFSET));
      var run =
          fullBox(
              "trun",
              TRUN_DATA_OFFSET | firstSampleFlags | TRUN_SAMPLE_COMPOSITION_TIME_OFFSET,
              u32(1),
              u32(dataOffset),
              keyframeFlags,
              entries.toByteArray());
      return box("traf", tfhd, fragment.extraVideoTrafBox, tfdt, run);
    }

    for (var index = 0; index < samples.size(); index++) {
      entries.writeBytes(u32(VIDEO_SAMPLE_DURATION + index));
      entries.writeBytes(u32(samples.get(index).length));
      entries.writeBytes(u32(index * 1001L));
    }

    var run =
        fullBox(
            "trun",
            TRUN_DATA_OFFSET
                | firstSampleFlags
                | TRUN_SAMPLE_DURATION
                | TRUN_SAMPLE_SIZE
                | TRUN_SAMPLE_COMPOSITION_TIME_OFFSET,
            u32(samples.size()),
            u32(dataOffset),
            keyframeFlags,
            entries.toByteArray());
    return box("traf", tfhd, fragment.extraVideoTrafBox, tfdt, run);
  }

  private static byte[] audioTraf(CopiedFragment fragment, int dataOffset) {
    var samples = fragment.audio;
    var tfhd = tfhdOf(AUDIO_TRACK_ID, AUDIO_SAMPLE_DURATION, samples, SYNC_SAMPLE_FLAGS);
    var tfdt = fullBox("tfdt", IsoBoxes.VERSION_1, IsoBoxes.u64(fragment.audioDecodeTime));
    if (samples.size() == 1) {
      return box("traf", tfhd, tfdt, fullBox("trun", TRUN_DATA_OFFSET, u32(1), u32(dataOffset)));
    }

    var sizes = new ByteArrayOutputStream();
    samples.forEach(sample -> sizes.writeBytes(u32(sample.length)));
    var run =
        fullBox(
            "trun",
            TRUN_DATA_OFFSET | TRUN_SAMPLE_SIZE,
            u32(samples.size()),
            u32(dataOffset),
            sizes.toByteArray());
    return box("traf", tfhd, tfdt, run);
  }

  private static byte[] tfhdOf(int trackId, int duration, List<byte[]> samples, int flags) {
    return fullBox(
        "tfhd",
        TFHD_DEFAULT_BASE_IS_MOOF
            | TFHD_DEFAULT_SAMPLE_DURATION
            | TFHD_DEFAULT_SAMPLE_SIZE
            | TFHD_DEFAULT_SAMPLE_FLAGS,
        u32(trackId),
        u32(duration),
        u32(samples.getFirst().length),
        u32(flags));
  }

  private static int bytesOf(List<byte[]> samples) {
    return samples.stream().mapToInt(sample -> sample.length).sum();
  }

  /** A NAL unit after a big-endian length prefix, of one type and its first payload byte. */
  static final class NalUnitBuilder {

    private final int type;
    private final OptionalInt firstPayloadByte;
    private int lengthSize = DEFAULT_LENGTH_SIZE;
    private OptionalInt picture = OptionalInt.empty();

    private NalUnitBuilder(int type, OptionalInt firstPayloadByte) {
      this.type = type;
      this.firstPayloadByte = firstPayloadByte;
    }

    /** The bytes of the length prefix: 1, 2 or 4. */
    NalUnitBuilder lengthSize(int lengthSize) {
      this.lengthSize = lengthSize;
      return this;
    }

    /** The number of the picture the NAL unit belongs to, as the payload's last byte. */
    NalUnitBuilder picture(int picture) {
      this.picture = OptionalInt.of(picture);
      return this;
    }

    byte[] build() {
      var unit = new ByteArrayOutputStream();
      unit.write(type << 1);
      unit.write(1);
      firstPayloadByte.ifPresent(unit::write);
      picture.ifPresent(unit::write);
      var length = new byte[lengthSize];
      for (var index = 0; index < lengthSize; index++) {
        length[index] = (byte) (unit.size() >>> (8 * (lengthSize - 1 - index)));
      }

      return concat(length, unit.toByteArray());
    }
  }

  /** The samples of one fragment and the decode time of each track's first. */
  static final class CopiedFragment {

    private final List<byte[]> video;
    private final List<byte[]> audio;
    private final long videoDecodeTime;
    private final long audioDecodeTime;
    private final boolean startsWithKeyframe;
    private final byte[] extraVideoTrafBox;
    private final byte[] extraMoofBox;

    private CopiedFragment(CopiedFragmentBuilder builder) {
      this.video = List.copyOf(builder.video);
      this.audio = List.copyOf(builder.audio);
      this.videoDecodeTime = builder.videoDecodeTime;
      this.audioDecodeTime = builder.audioDecodeTime;
      this.startsWithKeyframe = builder.startsWithKeyframe;
      this.extraVideoTrafBox = builder.extraVideoTrafBox;
      this.extraMoofBox = builder.extraMoofBox;
    }

    static final class CopiedFragmentBuilder {
      private final List<byte[]> video = new ArrayList<>();
      private final List<byte[]> audio = new ArrayList<>();
      private long videoDecodeTime;
      private long audioDecodeTime;
      private boolean startsWithKeyframe;
      private byte[] extraVideoTrafBox = new byte[0];
      private byte[] extraMoofBox = new byte[0];

      /** Whether the fragment's first video sample is a keyframe, a sync sample; else none is. */
      CopiedFragmentBuilder startsWithKeyframe(boolean startsWithKeyframe) {
        this.startsWithKeyframe = startsWithKeyframe;
        return this;
      }

      /** A box that the video traf holds after its tfhd, which FFmpeg does not write. */
      CopiedFragmentBuilder extraVideoTrafBox(byte[] box) {
        extraVideoTrafBox = box;
        return this;
      }

      /** A box that the moof holds after its mfhd, which FFmpeg does not write. */
      CopiedFragmentBuilder extraMoofBox(byte[] box) {
        extraMoofBox = box;
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
