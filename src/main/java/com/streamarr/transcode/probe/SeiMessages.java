package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;

/**
 * The HDR SEI messages of an H.264 or HEVC chosen video stream that the probe read: those of the
 * HEVC configuration record (hvcC), then those of the first packets, in order. FFmpeg stops parsing
 * an SEI NAL unit at the first message that it rejects, and ignores nested messages, so the probe
 * walks every SEI NAL unit itself, message by message, and reads inside the nesting messages.
 *
 * <p>The walk read all of the SEI when it delimited every NAL unit of the record and the packets,
 * and every SEI NAL unit among them: the payload of every message ends within its NAL unit, every
 * nested message ends within the payload of its nesting message, and only the RBSP trailing bits
 * follow the last message.
 */
record SeiMessages(List<SeiMessage> messages, boolean readAll) {

  static final int HDR10_PLUS = 4;
  static final int MASTERING_DISPLAY = 137;
  static final int CONTENT_LIGHT_LEVEL = 144;

  // An ITU-T T.35 message's country code 0xB5, provider code 0x003C, provider-oriented code 0x0001
  // and application identifier 4 make it an HDR10+ message.
  private static final byte[] HDR10_PLUS_IDENTIFIER = {(byte) 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04};
  private static final int TRAILING_BITS = 0x80;
  private static final int HEVC_RECORD_VERSION = 1;

  /** One HDR message: an HDR10+, mastering display or content light level message. */
  record SeiMessage(int payloadType, ByteBuffer payload, boolean nested) {}

  /**
   * The HDR SEI messages of the stream; empty unless the probe read the stream's first frames,
   * which only the chosen video stream has, and the stream's codec has NAL units.
   */
  static Optional<SeiMessages> of(JsonNode stream) {
    var syntax = NalSyntax.of(text(stream, "codec_name").orElse(""));
    if (syntax.isEmpty() || FirstFrames.of(stream).isEmpty()) {
      return Optional.empty();
    }

    var walk = new Walk(syntax.get());
    Extradata.of(stream)
        .filter(_ -> syntax.get() == NalSyntax.HEVC)
        .filter(extradata -> Byte.toUnsignedInt(extradata[0]) == HEVC_RECORD_VERSION)
        .map(DecoderConfiguration::hevcRecordUnits)
        .ifPresent(walk::add);
    FirstPacketSamples.read(stream, syntax.get())
        .ifPresentOrElse(samples -> samples.forEach(walk::add), walk::missUnits);
    return Optional.of(new SeiMessages(List.copyOf(walk.messages), walk.readAll));
  }

  /** Whether an HDR10+ message that is not nested shows. */
  boolean holdsHdr10Plus() {
    return messages.stream()
        .anyMatch(message -> message.payloadType() == HDR10_PLUS && !message.nested());
  }

  /** The payload of the first message of the type that is not nested, read-only. */
  Optional<ByteBuffer> firstPayload(int payloadType) {
    return messages.stream()
        .filter(message -> message.payloadType() == payloadType && !message.nested())
        .map(message -> message.payload().duplicate())
        .findFirst();
  }

  /**
   * Whether the walk rules out a message of the type: it read all of the SEI, and no nesting
   * message holds one. A nested message can apply only to a layer or a view that FFmpeg does not
   * decode, so it neither proves nor rules out a feature of the decoded picture.
   */
  boolean rulesOut(int payloadType) {
    return readAll
        && messages.stream()
            .noneMatch(message -> message.payloadType() == payloadType && message.nested());
  }

  private static final class Walk {

    private final NalSyntax syntax;
    private final List<SeiMessage> messages = new ArrayList<>();
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

    // FFmpeg drops a NAL unit's trailing zero bytes before it reads the RBSP.
    private void addSeiUnit(byte[] unit) {
      var end = unit.length;
      while (end > 0 && unit[end - 1] == 0) {
        end--;
      }

      var start = syntax.payloadOffset();
      if (start > end || !addMessages(ByteBuffer.wrap(unit, start, end - start), false)) {
        readAll = false;
      }
    }

    /** Adds the messages that the bytes hold; false when one of them runs past the bytes. */
    private boolean addMessages(ByteBuffer bytes, boolean nested) {
      while (bytes.hasRemaining() && !isTrailingBits(bytes)) {
        var payloadType = varyingLengthValue(bytes);
        var payloadSize = varyingLengthValue(bytes);
        if (payloadType.isEmpty()
            || payloadSize.isEmpty()
            || payloadSize.getAsLong() > bytes.remaining()) {
          return false;
        }

        var payload = new byte[(int) payloadSize.getAsLong()];
        bytes.get(payload);
        // A payloadType beyond the int range names no message that the walk looks for.
        var type = (int) Math.min(payloadType.getAsLong(), Integer.MAX_VALUE);
        if (!addMessage(type, payload, nested)) {
          return false;
        }
      }

      return true;
    }

    private boolean addMessage(int payloadType, byte[] payload, boolean nested) {
      if (SeiNesting.isNesting(syntax, payloadType)) {
        var offset = SeiNesting.nestedMessagesOffset(syntax, payloadType, payload);
        return offset.isPresent()
            && addMessages(
                ByteBuffer.wrap(payload, offset.getAsInt(), payload.length - offset.getAsInt()),
                true);
      }

      var isHdr = payloadType == MASTERING_DISPLAY || payloadType == CONTENT_LIGHT_LEVEL;
      if (isHdr || (payloadType == HDR10_PLUS && isHdr10Plus(payload))) {
        messages.add(
            new SeiMessage(payloadType, ByteBuffer.wrap(payload).asReadOnlyBuffer(), nested));
      }

      return true;
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

    private static boolean isHdr10Plus(byte[] payload) {
      return payload.length >= HDR10_PLUS_IDENTIFIER.length
          && Arrays.equals(
              payload,
              0,
              HDR10_PLUS_IDENTIFIER.length,
              HDR10_PLUS_IDENTIFIER,
              0,
              HDR10_PLUS_IDENTIFIER.length);
    }
  }
}
