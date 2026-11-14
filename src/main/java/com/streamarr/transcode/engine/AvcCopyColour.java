package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AvcSequenceParameterSet.ColourDescription;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class AvcCopyColour {

  private AvcCopyColour() {}

  static byte[] rewriteSampleEntry(ByteBuffer body, AssumedSourceMatrix matrix) {
    var entry = new BoxView("avc1", body);
    var children = entry.after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES).children();
    var record = configuration(children);
    var rewritten =
        record.sequenceParameterSets().stream()
            .map(sps -> AvcSequenceParameterSet.withMatrix(sps, matrix))
            .toList();
    var colour = AvcSequenceParameterSet.colourDescriptionOf(rewritten.getFirst());
    var result = new ByteArrayOutputStream();
    result.writeBytes(entry.fields().bytes(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES));
    for (var child : children) {
      if (child.type().equals("colr")) {
        continue;
      }

      var content =
          child.type().equals("avcC")
              ? record.withParameterSets(rewritten)
              : child.fields().bytes(child.body().remaining());
      result.writeBytes(box(child.type(), content));
    }

    result.writeBytes(box("colr", colourBoxBody(colour)));
    return result.toByteArray();
  }

  static void requireMatch(BoxView entry, AssumedSourceMatrix matrix) {
    if (!entry.type().equals("avc1") && !entry.type().equals("avc3")) {
      throw mismatch("the assumed source matrix rewrite requires an H.264 sample entry");
    }

    var children = entry.after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES).children();
    var colours =
        configuration(children).sequenceParameterSets().stream()
            .map(AvcSequenceParameterSet::colourDescriptionOf)
            .toList();
    var expectedMatrix = matrix == AssumedSourceMatrix.BT709 ? 1 : 6;
    if (colours.stream().anyMatch(colour -> colour.matrix() != expectedMatrix)) {
      throw mismatch("an avcC sequence parameter set does not name the assumed source matrix");
    }

    var boxes = children.stream().filter(child -> child.type().equals("colr")).toList();
    if (boxes.size() != 1) {
      throw mismatch("an H.264 copy naming its matrix requires exactly one colr box");
    }

    var declared = boxes.getFirst();
    if (!Arrays.equals(
        colourBoxBody(colours.getFirst()), declared.fields().bytes(declared.body().remaining()))) {
      throw mismatch(
          "the colr box differs from the first avcC sequence parameter set's planned colour description");
    }
  }

  private static DecoderConfiguration configuration(List<BoxView> children) {
    var records = children.stream().filter(child -> child.type().equals("avcC")).toList();
    if (records.size() != 1) {
      throw mismatch("the H.264 copy requires exactly one avcC record");
    }

    var fields = records.getFirst().fields();
    var prefix = fields.bytes(6);
    var count = prefix[5] & 0x1F;
    if (prefix[0] != 1 || count == 0) {
      throw mismatch("the avcC record requires version 1 and at least one sequence parameter set");
    }

    if ((prefix[4] & 3) == 2) {
      throw mismatch("the avcC record declares a reserved three-byte NAL unit length");
    }

    var sps = new ArrayList<byte[]>(count);
    for (var index = 0; index < count; index++) {
      sps.add(fields.bytes(fields.u16()));
    }

    return new DecoderConfiguration(prefix, sps, fields.bytes(fields.remaining()));
  }

  private static byte[] colourBoxBody(ColourDescription colour) {
    return ByteBuffer.allocate(11)
        .put("nclx".getBytes(StandardCharsets.US_ASCII))
        .putShort((short) (colour.primaries() == 2 ? 1 : colour.primaries()))
        .putShort((short) (colour.transfer() == 2 ? 1 : colour.transfer()))
        .putShort((short) colour.matrix())
        .put((byte) (colour.fullRange() ? 0x80 : 0))
        .array();
  }

  private static byte[] box(String type, byte[] body) {
    return ByteBuffer.allocate(8 + body.length)
        .putInt(8 + body.length)
        .put(type.getBytes(StandardCharsets.US_ASCII))
        .put(body)
        .array();
  }

  private record DecoderConfiguration(
      byte[] prefix, List<byte[]> sequenceParameterSets, byte[] tail) {
    byte[] withParameterSets(List<byte[]> rewritten) {
      var bytes = new ByteArrayOutputStream();
      bytes.writeBytes(prefix);
      for (var sps : rewritten) {
        if (sps.length > 0xFFFF) {
          throw mismatch("a rewritten SPS exceeds the avcC length field");
        }

        bytes.write(sps.length >> 8);
        bytes.write(sps.length);
        bytes.writeBytes(sps);
      }

      bytes.writeBytes(tail);
      return bytes.toByteArray();
    }
  }

  private static FragmentedMp4Exception mismatch(String detail) {
    return new FragmentedMp4Exception(FragmentedMp4Exception.Reason.OUTPUT_MISMATCH, detail);
  }
}
