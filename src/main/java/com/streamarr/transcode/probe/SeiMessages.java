package com.streamarr.transcode.probe;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The HDR SEI messages of an H.264 or HEVC chosen video stream that the probe read: those of the
 * extradata, as {@link NalSyntax#extradataUnits} reads it, then those of the first packets, in
 * order. FFmpeg stops parsing an SEI NAL unit at the first message that it rejects, and ignores
 * nested messages, so the probe walks every SEI NAL unit itself, message by message, and reads
 * inside the nesting messages.
 *
 * <p>The walk read all of the SEI when it delimited every NAL unit of the extradata and the
 * packets, and every SEI NAL unit among them: the payload of every message ends within its NAL
 * unit, every nested message ends within the payload of its nesting message, no nesting message
 * nests another, no message is a nesting message that the walk does not read inside, and only the
 * RBSP trailing bits follow the last message.
 */
final class SeiMessages implements MetadataWalk {

  private static final int HDR10_PLUS_PAYLOAD_TYPE = 4;
  private static final int MASTERING_DISPLAY_PAYLOAD_TYPE = 137;
  private static final int CONTENT_LIGHT_LEVEL_PAYLOAD_TYPE = 144;

  // An ITU-T T.35 message's country code 0xB5, provider code 0x003C, provider-oriented code 0x0001
  // and application identifier 4 make it an HDR10+ message.
  private static final byte[] HDR10_PLUS_IDENTIFIER = {(byte) 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04};
  private static final int TRAILING_BITS = 0x80;

  // The walk keeps the first payload of each kind and which kinds showed, not every message, so
  // that its memory stays bounded however many messages the packets hold.
  private final Map<HdrMessage, ByteBuffer> firstPayloads;
  private final Set<HdrMessage> nestedMessages;
  private final boolean readAll;
  private final Optional<RpuObservation> rpus;

  private SeiMessages(Walk walk, Optional<RpuObservation> rpus) {
    this.rpus = rpus;
    this.firstPayloads = walk.firstPayloads;
    this.nestedMessages = walk.nestedMessages;
    this.readAll = walk.readAll;
  }

  /**
   * The HDR SEI messages of the stream; empty unless the probe read the stream's first frames,
   * which only the chosen video stream has, and the stream's codec has NAL units.
   */
  static Optional<SeiMessages> of(JsonNode stream) {
    var syntax = ProbedCodecFamily.of(stream).nalSyntax();
    if (syntax.isEmpty() || FirstFrames.of(stream).isEmpty()) {
      return Optional.empty();
    }

    var walk = new Walk(syntax.get());
    if (WalkCoverage.missesUnits(stream)) {
      walk.missUnits();
    }

    Extradata.of(stream).map(syntax.get()::extradataUnits).ifPresent(walk::add);
    FirstPacketSamples.read(stream, syntax.get())
        .ifPresentOrElse(samples -> samples.forEach(walk::add), walk::missUnits);
    return Optional.of(new SeiMessages(walk, PacketRpuReader.read(stream, syntax.get())));
  }

  @Override
  public boolean holds(HdrMessage message) {
    return firstPayloads.containsKey(message);
  }

  @Override
  public Optional<ByteBuffer> firstPayload(HdrMessage message) {
    return Optional.ofNullable(firstPayloads.get(message)).map(ByteBuffer::duplicate);
  }

  @Override
  public Optional<RpuObservation> rpus() {
    return rpus;
  }

  @Override
  public boolean rulesOut(HdrMessage message) {
    return readAll && !nestedMessages.contains(message);
  }

  private static final class Walk {

    private final NalSyntax syntax;
    private final Map<HdrMessage, ByteBuffer> firstPayloads = new EnumMap<>(HdrMessage.class);
    private final Set<HdrMessage> nestedMessages = EnumSet.noneOf(HdrMessage.class);
    private boolean readAll = true;

    private Walk(NalSyntax syntax) {
      this.syntax = syntax;
    }

    void add(NalUnits units) {
      if (!units.complete()) {
        readAll = false;
      }

      units.units().stream().filter(syntax::isSei).forEach(this::addSeiUnit);
    }

    void missUnits() {
      readAll = false;
    }

    /**
     * The messages of an SEI NAL unit, which must end in the RBSP trailing bits: the byte 0x80,
     * after FFmpeg drops the NAL unit's trailing zero bytes. A unit that lacks them can have lost
     * messages, so it rules out none, but the messages that it holds still count.
     */
    private void addSeiUnit(byte[] unit) {
      var end = unit.length;
      while (end > 0 && unit[end - 1] == 0) {
        end--;
      }

      var start = syntax.payloadOffset();
      var isTerminated = end > start && Byte.toUnsignedInt(unit[end - 1]) == TRAILING_BITS;
      var messagesEnd = isTerminated ? end - 1 : end;
      var isDelimited =
          start <= messagesEnd
              && addMessages(ByteBuffer.wrap(unit, start, messagesEnd - start), false);
      if (!isTerminated || !isDelimited) {
        readAll = false;
      }
    }

    /** Adds the messages that the bytes hold; false when one of them runs past the bytes. */
    private boolean addMessages(ByteBuffer bytes, boolean nested) {
      // The top-level messages must fill the RBSP up to its trailing bits, which the caller
      // removed.
      // A nested payload may end in payload alignment bits.
      while (bytes.hasRemaining() && !(nested && isTrailingBits(bytes))) {
        var payloadType = varyingLengthValue(bytes);
        var payloadSize = varyingLengthValue(bytes);
        if (payloadType.isEmpty()
            || payloadSize.isEmpty()
            || payloadSize.getAsLong() > bytes.remaining()) {
          return false;
        }

        var size = (int) payloadSize.getAsLong();
        var payload = bytes.slice(bytes.position(), size);
        bytes.position(bytes.position() + size);
        // A payloadType beyond the int range names no message that the walk looks for.
        var type = (int) Math.min(payloadType.getAsLong(), Integer.MAX_VALUE);
        if (!addMessage(type, payload, nested)) {
          return false;
        }
      }

      return true;
    }

    /**
     * Adds one message, or the messages that a nesting message holds; false when the message is a
     * nesting message whose messages the walk cannot delimit.
     */
    private boolean addMessage(int payloadType, ByteBuffer payload, boolean nested) {
      if (SeiNesting.isUnreadNesting(syntax, payloadType)) {
        return false;
      }

      if (SeiNesting.isNesting(syntax, payloadType)) {
        return !nested && addNestedMessages(payloadType, payload);
      }

      kind(payloadType, payload).ifPresent(message -> add(message, payload, nested));
      return true;
    }

    private void add(HdrMessage message, ByteBuffer payload, boolean isNested) {
      if (isNested) {
        nestedMessages.add(message);
        return;
      }

      firstPayloads.computeIfAbsent(
          message,
          _ ->
              ByteBuffer.allocate(payload.remaining())
                  .put(payload.duplicate())
                  .flip()
                  .asReadOnlyBuffer());
    }

    private static Optional<HdrMessage> kind(int payloadType, ByteBuffer payload) {
      return switch (payloadType) {
        case MASTERING_DISPLAY_PAYLOAD_TYPE -> Optional.of(HdrMessage.MASTERING_DISPLAY);
        case CONTENT_LIGHT_LEVEL_PAYLOAD_TYPE -> Optional.of(HdrMessage.CONTENT_LIGHT_LEVEL);
        case HDR10_PLUS_PAYLOAD_TYPE ->
            Optional.of(HdrMessage.HDR10_PLUS).filter(_ -> isHdr10Plus(payload));
        default -> Optional.empty();
      };
    }

    /**
     * Adds the messages that a nesting message holds; false when its header or a nested message
     * runs past its payload. No nesting message may nest another, so the walk reads one level, and
     * a nesting message inside another rules out nothing that it could hold.
     */
    private boolean addNestedMessages(int payloadType, ByteBuffer payload) {
      var bytes = new byte[payload.remaining()];
      payload.duplicate().get(bytes);
      var offset = SeiNesting.nestedMessagesOffset(syntax, payloadType, bytes);
      return offset.isPresent()
          && addMessages(
              ByteBuffer.wrap(bytes, offset.getAsInt(), bytes.length - offset.getAsInt()), true);
    }

    private static boolean isTrailingBits(ByteBuffer bytes) {
      return bytes.remaining() == 1
          && Byte.toUnsignedInt(bytes.get(bytes.position())) == TRAILING_BITS;
    }

    /**
     * A payloadType or a payloadSize: a run of 0xFF bytes, each adding 255, then the last byte;
     * empty when the bytes end first.
     */
    private static OptionalLong varyingLengthValue(ByteBuffer bytes) {
      var value = 0L;
      int last;
      do {
        if (!bytes.hasRemaining()) {
          return OptionalLong.empty();
        }

        last = Byte.toUnsignedInt(bytes.get());
        value += last;
      } while (last == 0xFF);

      return OptionalLong.of(value);
    }

    private static boolean isHdr10Plus(ByteBuffer payload) {
      return payload.remaining() >= HDR10_PLUS_IDENTIFIER.length
          && payload
              .slice(payload.position(), HDR10_PLUS_IDENTIFIER.length)
              .equals(ByteBuffer.wrap(HDR10_PLUS_IDENTIFIER));
    }
  }
}
