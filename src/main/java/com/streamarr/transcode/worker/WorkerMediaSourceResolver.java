package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.MediaSourceRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves a media source beneath its namespace's root. The walk from the root's real path follows
 * no soft link: a link anywhere in the key's path refuses the source, wherever it points, because a
 * link can be swapped between this check and FFmpeg's open. Hard links are ordinary files.
 */
final class WorkerMediaSourceResolver {

  private final Map<UUID, Path> sourceNamespaces;

  WorkerMediaSourceResolver(Map<UUID, Path> sourceNamespaces) {
    this.sourceNamespaces = sourceNamespaces;
  }

  Path resolve(MediaSourceRef source) {
    validateSegments(source.getRelativeKey());
    var root = sourceNamespaces.get(fromProto(source.getSourceNamespaceId()));
    if (root == null) {
      throw new WorkerJobException("Unknown media source namespace");
    }

    try {
      var mediaFile = root.toRealPath();
      for (var segment : source.getRelativeKey().split("/")) {
        mediaFile = mediaFile.resolve(segment);
        if (attributesWithoutFollowingLinks(mediaFile).isSymbolicLink()) {
          throw new WorkerJobException("Media source path contains a soft link");
        }
      }

      if (!Files.isRegularFile(mediaFile, LinkOption.NOFOLLOW_LINKS)
          || !Files.isReadable(mediaFile)) {
        throw new WorkerJobException("Media source is not a readable file in its namespace");
      }
      return mediaFile;
    } catch (IOException | InvalidPathException e) {
      throw new WorkerJobException("Media source is unavailable", e);
    }
  }

  private static BasicFileAttributes attributesWithoutFollowingLinks(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  private void validateSegments(String relativeKey) {
    if (relativeKey.indexOf('\0') >= 0) {
      throw new WorkerJobException("Media source key must not contain NUL");
    }
    if (relativeKey.indexOf('\\') >= 0) {
      throw new WorkerJobException("Media source key must use '/' separators");
    }
    if (relativeKey.length() >= 2
        && Character.isLetter(relativeKey.charAt(0))
        && relativeKey.charAt(1) == ':') {
      throw new WorkerJobException("Media source key must not contain a drive prefix");
    }
    for (var segment : relativeKey.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw new WorkerJobException("Media source key contains an unsafe path segment");
      }
    }
  }
}
