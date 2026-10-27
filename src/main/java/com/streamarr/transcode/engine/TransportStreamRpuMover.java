package com.streamarr.transcode.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Copies the MPEG-TS that FFmpeg's muxer writes, moving the Dolby Vision RPU that an HEVC access
 * unit holds ahead of its first picture slice to the front of the access unit's PES payload, ahead
 * of the access unit delimiter.
 *
 * <p>FFmpeg's HEVC decoder reads an RPU only when it is the last NAL unit of the packet it decodes,
 * and an RPU ahead of a sample's picture belongs to the picture before. The MPEG-TS demuxer of the
 * FFmpeg that decodes this copy runs the HEVC parser, which starts an access unit at a delimiter
 * and never at an RPU, so the moved RPU ends the previous access unit, where the decoder reads it
 * for the picture it belongs to. Every byte stays within its PES payload, and every packet keeps
 * its length and its place, so timestamps, continuity counters and other streams' packets are
 * unchanged.
 */
final class TransportStreamRpuMover {

  static final int PACKET_BYTES = 188;
  private static final int SYNC_BYTE = 0x47;
  private static final int PAT_PID = 0;
  private static final int HEVC_STREAM_TYPE = 0x24;
  private static final int RPU = 62;
  // HEVC NAL unit types below this one hold picture slices.
  private static final int FIRST_NON_PICTURE_TYPE = 32;
  // FFmpeg's muxer writes the units ahead of a payload's first slice, parameter sets, SEI and the
  // RPU, in a few packets. A payload whose leading units do not end within this bound passes
  // unchanged, which bounds what the copy holds.
  private static final int MAXIMUM_HELD_PACKETS = 1024;

  private final InputStream input;
  private final OutputStream output;
  private final Set<Integer> programMapPids = new HashSet<>();
  private OptionalInt videoPid = OptionalInt.empty();
  private Optional<HeldPayload> held = Optional.empty();

  private TransportStreamRpuMover(InputStream input, OutputStream output) {
    this.input = input;
    this.output = output;
  }

  /**
   * Copies the stream to the end of the input. A packet out of sync, a payload that this copy
   * cannot follow, and video other than HEVC pass unchanged.
   */
  static void copy(InputStream input, OutputStream output) throws IOException {
    new TransportStreamRpuMover(input, output).copyToEnd();
  }

  private void copyToEnd() throws IOException {
    var packet = input.readNBytes(PACKET_BYTES);
    while (packet.length == PACKET_BYTES) {
      accept(packet);
      packet = input.readNBytes(PACKET_BYTES);
    }

    releaseHeld(true);
    output.write(packet);
    output.flush();
  }

  private void accept(byte[] packet) throws IOException {
    if (packet[0] != SYNC_BYTE) {
      releaseHeld(true);
      output.write(packet);
      return;
    }

    readProgramTables(packet);
    if (videoPid.isPresent() && pid(packet) == videoPid.getAsInt()) {
      acceptVideo(packet);
      return;
    }

    if (held.isPresent()) {
      held.orElseThrow().ride(packet);
      return;
    }

    output.write(packet);
  }

  private void acceptVideo(byte[] packet) throws IOException {
    if (startsPayload(packet)) {
      releaseHeld(true);
      held = Optional.of(HeldPayload.startingWith(packet));
    } else if (held.isPresent()) {
      held.orElseThrow().add(packet);
    } else {
      output.write(packet);
      return;
    }

    var payload = held.orElseThrow();
    if (payload.leadingUnits(false).isPresent()
        || payload.packets().size() >= MAXIMUM_HELD_PACKETS) {
      releaseHeld(false);
    }
  }

  // A payload is complete once the next one starts or the stream ends: its last unit ends with it.
  private void releaseHeld(boolean complete) throws IOException {
    if (held.isEmpty()) {
      return;
    }

    var payload = held.orElseThrow();
    held = Optional.empty();
    payload.leadingUnits(complete).ifPresent(payload::moveRpuToFront);
    for (var packet : payload.packets()) {
      output.write(packet);
    }
  }

  private void readProgramTables(byte[] packet) {
    if (!startsPayload(packet)) {
      return;
    }

    var pid = pid(packet);
    if (pid == PAT_PID) {
      section(packet).ifPresent(this::readProgramMapPids);
      return;
    }

    if (videoPid.isEmpty() && programMapPids.contains(pid)) {
      videoPid = section(packet).map(TransportStreamRpuMover::hevcPid).orElse(OptionalInt.empty());
    }
  }

  private void readProgramMapPids(Section pat) {
    for (var offset = 8; offset + 4 <= pat.endOfEntries(); offset += 4) {
      if (pat.unsigned16(offset) != 0) {
        programMapPids.add(pat.pid(offset + 2));
      }
    }
  }

  private static OptionalInt hevcPid(Section pmt) {
    if (pmt.endOfEntries() < 12) {
      return OptionalInt.empty();
    }

    var offset = 12 + (pmt.unsigned16(10) & 0x0FFF);
    while (offset + 5 <= pmt.endOfEntries()) {
      if (pmt.unsigned8(offset) == HEVC_STREAM_TYPE) {
        return OptionalInt.of(pmt.pid(offset + 1));
      }

      offset += 5 + (pmt.unsigned16(offset + 3) & 0x0FFF);
    }

    return OptionalInt.empty();
  }

  // The section that starts in the packet, cut at the packet's end; the reader never needs more.
  private static Optional<Section> section(byte[] packet) {
    var payloadStart = payloadStart(packet);
    if (payloadStart.isEmpty()) {
      return Optional.empty();
    }

    var start = payloadStart.getAsInt() + 1 + (packet[payloadStart.getAsInt()] & 0xFF);
    if (start + 3 > PACKET_BYTES) {
      return Optional.empty();
    }

    var bytes = Arrays.copyOfRange(packet, start, PACKET_BYTES);
    var sectionLength = ((bytes[1] & 0x0F) << 8) | (bytes[2] & 0xFF);
    // Four bytes of CRC close the section.
    return Optional.of(new Section(bytes, Math.min(3 + sectionLength - 4, bytes.length)));
  }

  private static int pid(byte[] packet) {
    return ((packet[1] & 0x1F) << 8) | (packet[2] & 0xFF);
  }

  private static boolean startsPayload(byte[] packet) {
    return (packet[1] & 0x40) != 0;
  }

  // Empty for a packet that holds only an adaptation field.
  private static OptionalInt payloadStart(byte[] packet) {
    var adaptationFieldControl = (packet[3] >> 4) & 0x03;
    return switch (adaptationFieldControl) {
      case 1 -> OptionalInt.of(4);
      case 3 -> {
        var start = 5 + (packet[4] & 0xFF);
        yield start < PACKET_BYTES ? OptionalInt.of(start) : OptionalInt.empty();
      }
      default -> OptionalInt.empty();
    };
  }

  private record Section(byte[] bytes, int endOfEntries) {

    int unsigned8(int offset) {
      return bytes[offset] & 0xFF;
    }

    int unsigned16(int offset) {
      return (unsigned8(offset) << 8) | unsigned8(offset + 1);
    }

    int pid(int offset) {
      return unsigned16(offset) & 0x1FFF;
    }
  }

  /**
   * The packets of one video PES payload, with the packets of other streams that arrived among
   * them, held until the NAL units ahead of its first picture slice are known.
   */
  private static final class HeldPayload {

    private final List<byte[]> packets = new ArrayList<>();
    private final List<Placement> placements = new ArrayList<>();
    private final ElementaryStream stream = new ElementaryStream();
    private boolean followable;

    static HeldPayload startingWith(byte[] packet) {
      var payload = new HeldPayload();
      payload.packets.add(packet);
      var start = pesPayloadStart(packet);
      payload.followable = start.isPresent();
      start.ifPresent(from -> payload.place(packet, from));
      return payload;
    }

    // Empty unless the PES header sits whole in the first packet, as FFmpeg's muxer writes it.
    private static OptionalInt pesPayloadStart(byte[] packet) {
      var start = payloadStart(packet);
      if (start.isEmpty()) {
        return OptionalInt.empty();
      }

      var header = start.getAsInt();
      var hasStartCode =
          header + 9 <= PACKET_BYTES
              && packet[header] == 0
              && packet[header + 1] == 0
              && packet[header + 2] == 1;
      if (!hasStartCode) {
        return OptionalInt.empty();
      }

      var elementaryStream = header + 9 + (packet[header + 8] & 0xFF);
      return elementaryStream <= PACKET_BYTES
          ? OptionalInt.of(elementaryStream)
          : OptionalInt.empty();
    }

    void add(byte[] packet) {
      packets.add(packet);
      payloadStart(packet).ifPresent(from -> place(packet, from));
    }

    void ride(byte[] packet) {
      packets.add(packet);
    }

    List<byte[]> packets() {
      return packets;
    }

    private void place(byte[] packet, int from) {
      placements.add(new Placement(packets.size() - 1, from, stream.length()));
      stream.append(packet, from);
    }

    /**
     * The units up to and including the RPU ahead of the first slice once known, which move nothing
     * when the RPU follows the first slice, already starts the payload, or is missing. Empty while
     * unknown; a complete payload is always known.
     */
    Optional<LeadingUnits> leadingUnits(boolean complete) {
      if (!followable) {
        return Optional.of(LeadingUnits.NONE);
      }

      var units = stream.units(complete);
      for (var index = 0; index < units.size(); index++) {
        var type = units.get(index).type();
        if (type.isEmpty()) {
          return undecided(complete);
        }

        if (type.getAsInt() < FIRST_NON_PICTURE_TYPE) {
          return Optional.of(LeadingUnits.NONE);
        }

        if (type.getAsInt() == RPU) {
          return throughRpuAt(units, index, complete);
        }
      }

      return undecided(complete);
    }

    private static Optional<LeadingUnits> throughRpuAt(
        List<NalUnit> units, int index, boolean complete) {
      var rpu = units.get(index);
      if (rpu.end().isEmpty()) {
        return undecided(complete);
      }

      return Optional.of(
          new LeadingUnits(units.getFirst().begin(), rpu.begin(), rpu.end().getAsInt()));
    }

    private static Optional<LeadingUnits> undecided(boolean complete) {
      return complete ? Optional.of(LeadingUnits.NONE) : Optional.empty();
    }

    // The RPU's bytes come first, then the units that preceded it; the bytes after the RPU stay.
    void moveRpuToFront(LeadingUnits leading) {
      if (leading.rpuBegin() == leading.begin()) {
        return;
      }

      var bytes = stream.bytes();
      var rpuLength = leading.rpuEnd() - leading.rpuBegin();
      var moved = new byte[leading.rpuEnd() - leading.begin()];
      System.arraycopy(bytes, leading.rpuBegin(), moved, 0, rpuLength);
      System.arraycopy(
          bytes, leading.begin(), moved, rpuLength, leading.rpuBegin() - leading.begin());
      for (var placement : placements) {
        placement.writeBack(packets.get(placement.packet()), moved, leading.begin());
      }
    }
  }

  /**
   * Where a packet's payload bytes lie in the elementary stream: the packet's index among those
   * held, the payload's offset in it, and the elementary-stream offset of its first byte.
   */
  private record Placement(int packet, int payloadOffset, int streamOffset) {

    // Writes the part of the replacement, which starts at that stream offset, that lies here.
    void writeBack(byte[] packetBytes, byte[] replacement, int replacementOffset) {
      var payloadLength = PACKET_BYTES - payloadOffset;
      var from = Math.max(streamOffset, replacementOffset);
      var to = Math.min(streamOffset + payloadLength, replacementOffset + replacement.length);
      if (from >= to) {
        return;
      }

      System.arraycopy(
          replacement,
          from - replacementOffset,
          packetBytes,
          payloadOffset + from - streamOffset,
          to - from);
    }
  }

  /**
   * The elementary-stream span from the payload's first unit through its RPU, and where the RPU
   * begins; a span that starts at the RPU moves nothing.
   */
  private record LeadingUnits(int begin, int rpuBegin, int rpuEnd) {
    static final LeadingUnits NONE = new LeadingUnits(0, 0, 0);
  }

  /**
   * One NAL unit of the elementary stream: where it begins, its type once its header byte has
   * arrived, and where it ends once the next start code, or the payload's end, has arrived.
   */
  private record NalUnit(int begin, OptionalInt type, OptionalInt end) {}

  /** The elementary-stream bytes of a held payload, delimited into Annex B NAL units. */
  private static final class ElementaryStream {

    private byte[] bytes = new byte[4 * PACKET_BYTES];
    private int length;
    // The offsets of the 00 00 01 of each start code, found as the bytes arrive.
    private final List<Integer> startCodes = new ArrayList<>();
    private int scanned;

    void append(byte[] packet, int from) {
      var added = PACKET_BYTES - from;
      if (length + added > bytes.length) {
        bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + added));
      }

      System.arraycopy(packet, from, bytes, length, added);
      length += added;
      scan();
    }

    int length() {
      return length;
    }

    byte[] bytes() {
      return bytes;
    }

    private void scan() {
      while (scanned + 3 <= length) {
        if (bytes[scanned] == 0 && bytes[scanned + 1] == 0 && bytes[scanned + 2] == 1) {
          startCodes.add(scanned);
          scanned += 3;
        } else {
          scanned++;
        }
      }
    }

    List<NalUnit> units(boolean complete) {
      var units = new ArrayList<NalUnit>();
      for (var index = 0; index < startCodes.size(); index++) {
        var headerByte = startCodes.get(index) + 3;
        var type =
            headerByte < length
                ? OptionalInt.of((bytes[headerByte] >> 1) & 0x3F)
                : OptionalInt.empty();
        var end = OptionalInt.empty();
        if (index + 1 < startCodes.size()) {
          end = OptionalInt.of(beginOf(startCodes.get(index + 1)));
        } else if (complete) {
          end = OptionalInt.of(length);
        }

        units.add(new NalUnit(beginOf(startCodes.get(index)), type, end));
      }

      return units;
    }

    // A four-byte start code's leading zero byte begins its unit.
    private int beginOf(int startCode) {
      return startCode > 0 && bytes[startCode - 1] == 0 ? startCode - 1 : startCode;
    }
  }
}
