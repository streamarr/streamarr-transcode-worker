package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/** A box inside a top-level box the reader already holds, viewed without copying its bytes. */
record NestedBox(@NonNull String type, @NonNull ByteBuffer body) {

  private static final int COMPACT_HEADER_BYTES = 8;
  private static final int LARGE_HEADER_BYTES = 16;
  private static final long LARGE_SIZE = 1;

  NestedBox {
    body = body.slice();
  }

  List<NestedBox> children(String childType) {
    var children = new ArrayList<NestedBox>();
    var content = body.duplicate();
    while (content.hasRemaining()) {
      var child = readChild(content);
      if (child.type().equals(childType)) {
        children.add(child);
      }
    }

    return children;
  }

  Optional<NestedBox> child(String childType) {
    return children(childType).stream().findFirst();
  }

  NestedBox requiredChild(String childType) {
    return child(childType).orElseThrow(() -> malformed(type + " holds no " + childType));
  }

  BoxFields fields() {
    return new BoxFields(type, body.duplicate());
  }

  static FragmentedMp4Exception malformed(String detail) {
    return new FragmentedMp4Exception(Reason.MALFORMED_BOX, detail);
  }

  private NestedBox readChild(ByteBuffer content) {
    var fields = new BoxFields(type, content);
    var size = fields.u32();
    var childType = fields.fourcc();
    var headerBytes = COMPACT_HEADER_BYTES;
    if (size == LARGE_SIZE) {
      size = fields.s64();
      headerBytes = LARGE_HEADER_BYTES;
    }

    var bodyBytes = size - headerBytes;
    if (bodyBytes < 0 || bodyBytes > content.remaining()) {
      throw malformed(childType + " in " + type + " declares " + size + " bytes");
    }

    var childBody = content.slice(content.position(), (int) bodyBytes);
    content.position(content.position() + (int) bodyBytes);
    return new NestedBox(childType, childBody);
  }
}
