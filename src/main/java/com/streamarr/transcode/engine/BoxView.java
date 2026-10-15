package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;

/** A box's type and the body bytes the reader already holds, viewed without copying them. */
record BoxView(@NonNull String type, @NonNull ByteBuffer body) {

  BoxView {
    body = body.slice();
  }

  List<BoxView> children(String childType) {
    return children().stream().filter(child -> child.type().equals(childType)).toList();
  }

  /** Every child box in order, read from the whole body. */
  List<BoxView> children() {
    var children = new ArrayList<BoxView>();
    var content = body.duplicate();
    while (content.hasRemaining()) {
      children.add(readChild(content));
    }

    return children;
  }

  /** The boxes that follow the first bytes of the body, such as a sample entry's fixed fields. */
  BoxView after(int bytes) {
    if (bytes > body.remaining()) {
      throw FragmentedMp4Exception.malformed(type + " ends before its fields do");
    }

    return new BoxView(type, body.slice(bytes, body.remaining() - bytes));
  }

  BoxView requiredChild(String childType) {
    return children(childType).stream()
        .findFirst()
        .orElseThrow(() -> FragmentedMp4Exception.malformed(type + " holds no " + childType));
  }

  BoxFields fields() {
    return new BoxFields(type, body.duplicate());
  }

  private BoxView readChild(ByteBuffer content) {
    var header = BoxHeader.read(new BoxFields(type, content));
    var bodyBytes = header.size() - header.length();
    if (bodyBytes < 0 || bodyBytes > content.remaining()) {
      throw FragmentedMp4Exception.malformed(
          header.type() + " in " + type + " declares " + header.size() + " bytes");
    }

    var childBody = content.slice(content.position(), (int) bodyBytes);
    content.position(content.position() + (int) bodyBytes);
    return new BoxView(header.type(), childBody);
  }
}
