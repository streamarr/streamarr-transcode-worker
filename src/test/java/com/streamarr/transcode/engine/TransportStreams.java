package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds MPEG-TS the way FFmpeg's muxer lays it out: 188-byte packets, a PAT and a PMT, and PES
 * packets whose last TS packet pads its payload with an adaptation field.
 */
final class TransportStreams {

  static final int PACKET_BYTES = 188;
  static final int PMT_PID = 0x1000;
  static final int VIDEO_PID = 0x100;
  static final int AUDIO_PID = 0x101;
  static final int HEVC = 0x24;
  static final int H264 = 0x1B;
  static final int AAC = 0x0F;

  static final int AUD = 35;
  static final int VPS = 32;
  static final int SPS = 33;
  static final int PPS = 34;
  static final int PREFIX_SEI = 39;
  static final int RPU = 62;
  static final int TRAIL_R = 1;
  static final int IDR = 19;

  private static final int PAYLOAD_BYTES = 184;

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final int[] continuityCounters = new int[0x2000];

  /** A PAT naming one program, and its PMT with the video and an AAC audio stream. */
  static TransportStreams withProgram(int videoStreamType) {
    var stream = new TransportStreams();
    stream.section(0, pat());
    stream.section(PMT_PID, pmt(videoStreamType));
    return stream;
  }

  /** Writes one PES packet that carries the bytes, starting a new payload unit. */
  TransportStreams pes(int pid, byte[] elementaryStream) {
    var header = new byte[] {0, 0, 1, (byte) (pid == AUDIO_PID ? 0xC0 : 0xE0), 0, 0, (byte) 0x80};
    var pesHeaderData = new byte[] {(byte) 0x80, 5, 0x21, 0, 1, 0, 1};
    var payload = concat(header, pesHeaderData, elementaryStream);
    for (var offset = 0; offset < payload.length; offset += PAYLOAD_BYTES) {
      var end = Math.min(payload.length, offset + PAYLOAD_BYTES);
      packet(pid, offset == 0, Arrays.copyOfRange(payload, offset, end));
    }

    return this;
  }

  /** Appends bytes that do not make a whole packet. */
  TransportStreams trailing(byte[] partialPacket) {
    bytes.writeBytes(partialPacket);
    return this;
  }

  byte[] bytes() {
    return bytes.toByteArray();
  }

  private void section(int pid, byte[] section) {
    packet(pid, true, concat(new byte[] {0}, section));
  }

  // A payload shorter than a packet holds is padded in front with an adaptation field.
  private void packet(int pid, boolean payloadUnitStart, byte[] payload) {
    var packet = new byte[PACKET_BYTES];
    packet[0] = 0x47;
    packet[1] = (byte) ((payloadUnitStart ? 0x40 : 0) | (pid >> 8));
    packet[2] = (byte) pid;
    var counter = continuityCounters[pid]++ & 0x0F;
    var stuffing = PAYLOAD_BYTES - payload.length;
    if (stuffing == 0) {
      packet[3] = (byte) (0x10 | counter);
      System.arraycopy(payload, 0, packet, 4, payload.length);
      bytes.writeBytes(packet);
      return;
    }

    packet[3] = (byte) (0x30 | counter);
    packet[4] = (byte) (stuffing - 1);
    if (stuffing > 1) {
      packet[5] = 0;
      Arrays.fill(packet, 6, 4 + stuffing, (byte) 0xFF);
    }

    System.arraycopy(payload, 0, packet, 4 + stuffing, payload.length);
    bytes.writeBytes(packet);
  }

  private static byte[] pat() {
    return withCrc(
        new byte[] {0x00, (byte) 0xB0, 13, 0, 1, (byte) 0xC1, 0, 0, 0, 1, (byte) 0xF0, 0});
  }

  private static byte[] pmt(int videoStreamType) {
    return withCrc(
        new byte[] {
          0x02,
          (byte) 0xB0,
          23,
          0,
          1,
          (byte) 0xC1,
          0,
          0,
          (byte) 0xE1,
          0x00,
          (byte) 0xF0,
          0,
          (byte) videoStreamType,
          (byte) 0xE1,
          0x00,
          (byte) 0xF0,
          0,
          AAC,
          (byte) 0xE1,
          0x01,
          (byte) 0xF0,
          0
        });
  }

  // The reader never checks the CRC, so four placeholder bytes stand in for it.
  private static byte[] withCrc(byte[] section) {
    return concat(section, new byte[] {0x12, 0x34, 0x56, 0x78});
  }

  /** An HEVC NAL unit of the type behind a four-byte start code, with a payload of the size. */
  static byte[] nalUnit(int type, int payloadBytes) {
    var unit = new byte[6 + payloadBytes];
    unit[3] = 1;
    unit[4] = (byte) (type << 1);
    unit[5] = 1;
    for (var index = 0; index < payloadBytes; index++) {
      unit[6 + index] = (byte) (0x40 + (type + index) % 0x40);
    }

    return unit;
  }

  /** An elementary stream of NAL units, each of the type, with small payloads. */
  static byte[] accessUnit(int... types) {
    var units = new ArrayList<byte[]>();
    for (var type : types) {
      units.add(nalUnit(type, type == RPU ? 30 : 12));
    }

    return concat(units.toArray(byte[][]::new));
  }

  static byte[] concat(byte[]... parts) {
    var joined = new ByteArrayOutputStream();
    List.of(parts).forEach(joined::writeBytes);
    return joined.toByteArray();
  }
}
