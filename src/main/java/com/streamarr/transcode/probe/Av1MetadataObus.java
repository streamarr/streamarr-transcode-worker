package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.probe.BitReader.TruncatedBitstreamException;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The HDR metadata OBUs of an AV1 chosen video stream that the probe read: those of the av1C
 * record's configOBUs, then those of the first packets, in order. FFmpeg's AV1 decoders drop the
 * rest of a packet from an OBU that they cannot parse, drop a frame whose HDR10+ message they
 * cannot parse, and never apply the record's metadata OBUs, so the probe walks every OBU itself, in
 * the low-overhead bitstream format of AV1 section 5.
 *
 * <p>An OBU is delimited when its header, its extension byte, its obu_size of at most 8 bytes and
 * its payload end within its packet or record; an OBU without a size field runs to the end of
 * either. A metadata OBU is delimited when its metadata_type also ends within the OBU and, for
 * HDR_CLL, HDR_MDCV and ITUT_T35, its last nonzero byte is 0x80: the trailing bits. The walk read
 * all of the OBUs when it delimited every one. A metadata OBU with an extension byte names a layer
 * that FFmpeg's decoders may skip, so it counts as a nested SEI message does.
 */
final class Av1MetadataObus implements MetadataWalk {

  private static final int METADATA_OBU = 5;
  private static final int HDR_CLL = 1;
  private static final int HDR_MDCV = 2;
  private static final int ITUT_T35 = 4;
  private static final int TRAILING_BITS = 0x80;
  private static final int LONGEST_LEB128 = 8;
  private static final long LARGEST_OBU_SIZE = 0xFFFF_FFFFL;

  // ITU-T T.35 country code 0xB5, then the provider code and the provider-oriented code, and for
  // HDR10+ the application identifier 4.
  private static final byte[] HDR10_PLUS_IDENTIFIER = {(byte) 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04};
  private static final byte[] RPU_IDENTIFIER = {(byte) 0xB5, 0x00, 0x3B, 0x00, 0x00, 0x08, 0x00};

  // The EMDF container around an RPU, as FFmpeg 8.1 opens it: a fixed 27-bit header, a payload
  // size of 6 to 512 bytes, and a fixed 17-bit emdf_protection.
  private static final long EMDF_HEADER = 0x01BE6841L;
  private static final long EMDF_PROTECTION = 0x400;
  private static final int SMALLEST_EMDF_PAYLOAD = 6;
  private static final int LARGEST_EMDF_PAYLOAD = 512;

  // The walk keeps the first payload of each kind and which kinds showed, not every OBU, so that
  // its memory stays bounded however many OBUs the packets hold.
  private final Map<HdrMessage, ByteBuffer> firstPayloads;
  private final Set<HdrMessage> layeredMessages;
  private final Optional<byte[]> firstRpu;
  private final boolean hasLayeredRpu;
  private final boolean readAll;

  private Av1MetadataObus(Walk walk) {
    this.firstPayloads = walk.firstPayloads;
    this.layeredMessages = walk.layeredMessages;
    this.firstRpu = Optional.ofNullable(walk.firstRpu);
    this.hasLayeredRpu = walk.hasLayeredRpu;
    this.readAll = walk.readAll;
  }

  /**
   * The metadata OBUs of the stream; empty unless the stream is AV1 and the probe read its first
   * frames, which only the chosen video stream has.
   */
  static Optional<Av1MetadataObus> of(JsonNode stream) {
    if (!"av1".equals(stream.path("codec_name").asString("")) || FirstFrames.of(stream).isEmpty()) {
      return Optional.empty();
    }

    var walk = new Walk();
    if (WalkCoverage.missesUnits(stream)) {
      walk.readAll = false;
    }

    Extradata.of(stream)
        .filter(DecoderConfiguration::isAv1Record)
        .ifPresent(
            record ->
                walk.add(record, DecoderConfiguration.AV1_RECORD_HEADER_BYTES, record.length));
    FirstPackets.of(stream)
        .ifPresentOrElse(
            packets -> packets.forEach(packet -> walk.add(packet, 0, packet.length)),
            () -> walk.readAll = false);
    return Optional.of(new Av1MetadataObus(walk));
  }

  @Override
  public boolean holds(HdrMessage message) {
    return firstPayloads.containsKey(message);
  }

  @Override
  public boolean rulesOut(HdrMessage message) {
    return readAll && !layeredMessages.contains(message);
  }

  /**
   * The bytes ahead of the trailing bits of the first OBU of the kind without an extension byte:
   * for HDR_MDCV and HDR_CLL, the values after metadata_type. Empty bytes when that OBU lacks its
   * trailing bits.
   */
  @Override
  public Optional<ByteBuffer> firstPayload(HdrMessage message) {
    return Optional.ofNullable(firstPayloads.get(message)).map(ByteBuffer::duplicate);
  }

  /**
   * PRESENT, with the header of the first RPU, when an RPU OBU without an extension byte shows.
   * ABSENT when the walk read all of the OBUs and no OBU with an extension byte holds an RPU.
   * FFmpeg's AV1 decoders attach an RPU to the next shown frame, so an RPU ahead of its frame is
   * the layout that AV1 expects, and the walk never tells the placement.
   */
  @Override
  public Optional<RpuObservation> rpus() {
    if (firstRpu.isPresent()) {
      return Optional.of(
          new RpuObservation(
              Presence.PRESENCE_PRESENT,
              header(firstRpu.get()),
              DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED));
    }

    if (readAll && !hasLayeredRpu) {
      return Optional.of(
          new RpuObservation(
              Presence.PRESENCE_ABSENT,
              Optional.empty(),
              DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED));
    }

    return Optional.empty();
  }

  /**
   * The header of the RPU that the EMDF container after the T.35 identifier wraps; empty when the
   * container is not one that FFmpeg opens.
   */
  private static Optional<DolbyVisionRpuHeader> header(byte[] container) {
    var reader = new BitReader(container);
    try {
      if (reader.bits(27) != EMDF_HEADER) {
        return Optional.empty();
      }

      var size = variableBits(reader);
      if (size < SMALLEST_EMDF_PAYLOAD || size > LARGEST_EMDF_PAYLOAD) {
        return Optional.empty();
      }

      var rpu = new byte[(int) size];
      for (var index = 0; index < rpu.length; index++) {
        rpu[index] = (byte) reader.bits(8);
      }

      if (reader.bits(17) != EMDF_PROTECTION) {
        return Optional.empty();
      }

      return DolbyVisionRpuHeaderReader.readWithoutPrefix(rpu);
    } catch (TruncatedBitstreamException _) {
      return Optional.empty();
    }
  }

  /**
   * EMDF's variable_bits(8): 8 bits, then while a read_more bit is set, the value plus one shifted
   * by 8 with the next 8 bits. The value stops growing past the largest payload that the walk
   * accepts, so that it cannot overflow.
   */
  private static long variableBits(BitReader reader) {
    var value = reader.bits(8);
    while (reader.flag()) {
      value = Math.min(((value + 1) << 8) | reader.bits(8), LARGEST_EMDF_PAYLOAD + 1L);
    }

    return value;
  }

  private static final class Walk {

    private final Map<HdrMessage, ByteBuffer> firstPayloads = new EnumMap<>(HdrMessage.class);
    private final Set<HdrMessage> layeredMessages = EnumSet.noneOf(HdrMessage.class);
    private byte[] firstRpu;
    private boolean hasLayeredRpu;
    private boolean readAll = true;

    /** Walks the OBUs from {@code start} to {@code end}, and stops at one it cannot delimit. */
    void add(byte[] bytes, int start, int end) {
      var position = start;
      while (position < end) {
        var next = addObu(bytes, position, end);
        if (next.isEmpty()) {
          readAll = false;
          return;
        }

        position = (int) next.getAsLong();
      }
    }

    /** Adds one OBU, and returns where the next one starts; empty when it is not delimited. */
    private OptionalLong addObu(byte[] bytes, int start, int end) {
      var header = Byte.toUnsignedInt(bytes[start]);
      var type = (header >> 3) & 0x0F;
      var isLayered = (header & 0x04) != 0;
      var hasSizeField = (header & 0x02) != 0;
      var position = start + 1 + (isLayered ? 1 : 0);
      if (position > end) {
        return OptionalLong.empty();
      }

      var payload =
          hasSizeField
              ? sizedPayload(new Region(bytes, position, end))
              : Optional.of(new Region(bytes, position, end));
      if (payload.isEmpty()) {
        return OptionalLong.empty();
      }

      var isDelimited = type != METADATA_OBU || addMetadata(payload.get(), isLayered);
      return isDelimited ? OptionalLong.of(payload.get().end()) : OptionalLong.empty();
    }

    /** The payload that the obu_size at the region's start gives; empty when it runs past. */
    private static Optional<Region> sizedPayload(Region rest) {
      return leb128(rest)
          .filter(size -> size.end() + size.value() <= rest.end())
          .map(size -> new Region(rest.bytes(), size.end(), (int) (size.end() + size.value())));
    }

    /** Adds a metadata OBU; false when it is not delimited. */
    private boolean addMetadata(Region payload, boolean isLayered) {
      var metadataType = leb128(payload);
      if (metadataType.isEmpty()) {
        return false;
      }

      var type = metadataType.get().value();
      if (type != HDR_CLL && type != HDR_MDCV && type != ITUT_T35) {
        return true;
      }

      var bytes = payload.bytes();
      var valuesStart = metadataType.get().end();
      var last = payload.end() - 1;
      while (last >= valuesStart && bytes[last] == 0) {
        last--;
      }

      var isTerminated = last >= valuesStart && Byte.toUnsignedInt(bytes[last]) == TRAILING_BITS;
      var values =
          ByteBuffer.wrap(bytes, valuesStart, (isTerminated ? last : payload.end()) - valuesStart);
      // A value that lacks its trailing bits keeps no bytes, so that the probe cannot report it.
      var staticValues = isTerminated ? values : ByteBuffer.allocate(0);
      switch ((int) type) {
        case HDR_CLL -> addStatic(HdrMessage.CONTENT_LIGHT_LEVEL, staticValues, isLayered);
        case HDR_MDCV -> addStatic(HdrMessage.MASTERING_DISPLAY, staticValues, isLayered);
        default -> addT35(values, isLayered);
      }

      return isTerminated;
    }

    private void addStatic(HdrMessage message, ByteBuffer values, boolean isLayered) {
      if (isLayered) {
        layeredMessages.add(message);
        return;
      }

      firstPayloads.computeIfAbsent(message, _ -> copy(values));
    }

    private void addT35(ByteBuffer values, boolean isLayered) {
      if (startsWith(values, HDR10_PLUS_IDENTIFIER)) {
        addHdr10Plus(values, isLayered);
        return;
      }

      if (!startsWith(values, RPU_IDENTIFIER)) {
        return;
      }

      if (isLayered) {
        hasLayeredRpu = true;
        return;
      }

      if (firstRpu == null) {
        var container = values.duplicate().position(values.position() + RPU_IDENTIFIER.length);
        firstRpu = new byte[container.remaining()];
        container.get(firstRpu);
      }
    }

    private void addHdr10Plus(ByteBuffer values, boolean isLayered) {
      if (isLayered) {
        layeredMessages.add(HdrMessage.HDR10_PLUS);
        return;
      }

      firstPayloads.computeIfAbsent(HdrMessage.HDR10_PLUS, _ -> copy(values));
    }

    private static ByteBuffer copy(ByteBuffer values) {
      return ByteBuffer.allocate(values.remaining())
          .put(values.duplicate())
          .flip()
          .asReadOnlyBuffer();
    }

    private static boolean startsWith(ByteBuffer values, byte[] identifier) {
      return values.remaining() >= identifier.length
          && values.slice(values.position(), identifier.length).equals(ByteBuffer.wrap(identifier));
    }

    /**
     * The leb128 value at the region's start, of at most 8 bytes and fitting 32 bits, as dav1d
     * accepts it; empty when it runs past the region, takes more bytes, or is larger.
     */
    private static Optional<Leb128> leb128(Region region) {
      var value = 0L;
      for (var index = 0; index < LONGEST_LEB128; index++) {
        var position = region.start() + index;
        if (position >= region.end()) {
          return Optional.empty();
        }

        var octet = Byte.toUnsignedInt(region.bytes()[position]);
        value |= (long) (octet & 0x7F) << (7 * index);
        if ((octet & 0x80) == 0) {
          return value > LARGEST_OBU_SIZE
              ? Optional.empty()
              : Optional.of(new Leb128(value, position + 1));
        }
      }

      return Optional.empty();
    }
  }

  /** The bytes from {@code start} up to {@code end} of an array. */
  private record Region(byte[] bytes, int start, int end) {}

  /** A leb128 value, and the offset after its last byte. */
  private record Leb128(long value, int end) {}
}
