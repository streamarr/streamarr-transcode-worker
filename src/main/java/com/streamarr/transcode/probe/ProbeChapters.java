package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ProbeChapter;
import build.buf.gen.streamarr.transcode.v1.ProbeContainerInfo;
import com.google.protobuf.CodedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The container's chapters, as ffprobe -show_chapters lists them, in ffprobe's order and with the
 * values ffprobe reports. Each time is the chapter's start or end ticks of its time base, on the
 * probe's timeline. A title takes at most 512 bytes of UTF-8; a longer title is absent.
 */
final class ProbeChapters {

  /** The chapters of one result take at most this many bytes in their protobuf encoding. */
  private static final int MAXIMUM_ENCODED_BYTES = 32_768;

  private static final int MAXIMUM_TITLE_BYTES = 512;

  private ProbeChapters() {}

  /** Adds the chapters, or marks them omitted when they would take too many bytes. */
  static void add(JsonNode chapters, ProbeTimeline timeline, ProbeContainerInfo.Builder container) {
    var read = chapters.valueStream().map(chapter -> chapter(chapter, timeline)).toList();
    if (encodedBytes(read) > MAXIMUM_ENCODED_BYTES) {
      container.setChaptersOmitted(true);
      return;
    }

    container.addAllChapters(read);
  }

  private static ProbeChapter chapter(JsonNode source, ProbeTimeline timeline) {
    var chapter = ProbeChapter.newBuilder();
    var timeBase = ProbeTimeline.timeBase(source, "time_base");
    timeBase
        .flatMap(base -> ProbeTimeline.ticks(source, "start").map(t -> timeline.at(t, base)))
        .ifPresent(chapter::setStartTime);
    timeBase
        .flatMap(base -> ProbeTimeline.ticks(source, "end").map(t -> timeline.at(t, base)))
        .ifPresent(chapter::setEndTime);
    StreamTags.tag(source, "title")
        .filter(title -> title.getBytes(StandardCharsets.UTF_8).length <= MAXIMUM_TITLE_BYTES)
        .ifPresent(chapter::setTitle);
    return chapter.build();
  }

  private static int encodedBytes(List<ProbeChapter> chapters) {
    return chapters.stream()
        .mapToInt(
            chapter ->
                CodedOutputStream.computeMessageSize(
                    ProbeContainerInfo.CHAPTERS_FIELD_NUMBER, chapter))
        .sum();
  }
}
