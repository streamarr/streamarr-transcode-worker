package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/** A box's type and the body bytes the reader already holds, viewed without copying them. */
record BoxView(@NonNull String type, @NonNull ByteBuffer body) {

  BoxView {
    body = body.slice();
  }

  List<BoxView> children(String childType) {
    var children = new ArrayList<BoxView>();
    var content = body.duplicate();
    while (content.hasRemaining()) {
      var child = readChild(content);
      if (child.type().equals(childType)) {
        children.add(child);
      }
    }

    return children;
  }

  Optional<BoxView> child(String childType) {
    return children(childType).stream().findFirst();
  }

  BoxView requiredChild(String childType) {
    return child(childType)
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
