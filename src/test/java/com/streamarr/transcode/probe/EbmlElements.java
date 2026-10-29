package com.streamarr.transcode.probe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Singular;

/** Builds the bytes of EBML elements, for the tests that read Matroska files. */
final class EbmlElements {

  static final long EBML = 0x1A45DFA3;
  static final long DOC_TYPE = 0x4282;
  static final long SEGMENT = 0x18538067;
  static final long VOID = 0xEC;
  static final long SEEK_HEAD = 0x114D9B74;
  static final long SEEK = 0x4DBB;
  static final long SEEK_ID = 0x53AB;
  static final long SEEK_POSITION = 0x53AC;
  static final long INFO = 0x1549A966;
  static final long TIMESTAMP_SCALE = 0x2AD7B1;
  static final long DURATION = 0x4489;
  static final long MUXING_APP = 0x4D80;
  static final long WRITING_APP = 0x5741;
  static final long TRACKS = 0x1654AE6B;
  static final long TRACK_ENTRY = 0xAE;
  static final long TRACK_NUMBER = 0xD7;
  static final long TRACK_TYPE = 0x83;
  static final long CODEC_ID = 0x86;
  static final long TRACK_TIMESTAMP_SCALE = 0x23314F;
  static final long VIDEO = 0xE0;
  static final long PIXEL_WIDTH = 0xB0;
  static final long COLOUR = 0x55B0;
  static final long RANGE = 0x55B9;
  static final long MAX_CLL = 0x55BC;
  static final long MAX_FALL = 0x55BD;
  static final long MASTERING_METADATA = 0x55D0;
  static final long PRIMARY_R_CHROMATICITY_X = 0x55D1;
  static final long LUMINANCE_MAX = 0x55D9;
  static final long CUES = 0x1C53BB6B;
  static final long CUE_POINT = 0xBB;
  static final long CUE_TIME = 0xB3;
  static final long CUE_TRACK_POSITIONS = 0xB7;
  static final long CUE_TRACK = 0xF7;
  static final long CUE_CLUSTER_POSITION = 0xF1;
  static final long CLUSTER = 0x1F43B675;
  static final long CLUSTER_TIMESTAMP = 0xE7;

  static final long VIDEO_TRACK_TYPE = 1;
  static final long AUDIO_TRACK_TYPE = 2;

  static final String HEVC_CODEC_ID = "V_MPEGH/ISO/HEVC";

  private EbmlElements() {}

  /** The EBML header, then a Segment that holds {@code segmentChildren}. */
  static byte[] document(byte[]... segmentChildren) {
    return concat(header(), element(SEGMENT, segmentChildren));
  }

  /** The EBML header of a Matroska file. */
  static byte[] header() {
    return element(EBML, element(DOC_TYPE, "matroska".getBytes(StandardCharsets.UTF_8)));
  }

  /** The CodecID element of an HEVC video track; FFmpeg keeps a video track only with one. */
  static byte[] hevcCodecId() {
    return codecId(HEVC_CODEC_ID);
  }

  static byte[] codecId(String codec) {
    return element(CODEC_ID, codec.getBytes(StandardCharsets.US_ASCII));
  }

  static byte[] element(long id, byte[]... children) {
    var payload = concat(children);
    return concat(elementHeader(id, payload.length), payload);
  }

  /** The ID and 8-byte size of an element whose payload takes {@code size} bytes. */
  static byte[] elementHeader(long id, long size) {
    var sizeBytes = ByteBuffer.allocate(8).putLong(size).array();
    sizeBytes[0] = 0x01;
    return concat(unsigned(id), sizeBytes);
  }

  static byte[] unsigned(long value) {
    var bytes = ByteBuffer.allocate(8).putLong(value).array();
    var first = 0;
    while (first < 7 && bytes[first] == 0) {
      first++;
    }

    var trimmed = new byte[8 - first];
    System.arraycopy(bytes, first, trimmed, 0, trimmed.length);
    return trimmed;
  }

  static byte[] concat(byte[]... parts) {
    var output = new ByteArrayOutputStream();
    for (var part : parts) {
      output.writeBytes(part);
    }

    return output.toByteArray();
  }

  /**
   * A TrackEntry: its TrackNumber, TrackType and CodecID, then {@code children} in order. A null
   * {@code codecId} writes no CodecID.
   */
  @Builder
  record TrackEntry(long number, long type, String codecId, @Singular List<byte[]> children) {

    /** The first track, a video track with an HEVC CodecID. */
    static TrackEntryBuilder video() {
      return builder().number(1).type(VIDEO_TRACK_TYPE).codecId(HEVC_CODEC_ID);
    }

    byte[] bytes() {
      var elements = new ArrayList<byte[]>();
      elements.add(element(TRACK_NUMBER, unsigned(number)));
      elements.add(element(TRACK_TYPE, unsigned(type)));
      if (codecId != null) {
        elements.add(EbmlElements.codecId(codecId));
      }

      elements.addAll(children);
      return element(TRACK_ENTRY, elements.toArray(byte[][]::new));
    }
  }
}
