package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.Mp4MovieBox.children;
import static com.streamarr.transcode.probe.Mp4MovieBox.fourCharacterCode;
import static com.streamarr.transcode.probe.Mp4MovieBox.skip;
import static com.streamarr.transcode.probe.Mp4MovieBox.skipFlags;
import static com.streamarr.transcode.probe.Mp4SampleTables.runEntries;

import com.streamarr.transcode.probe.Mp4SampleTables.Run;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.List;

/**
 * Reads the random-access sample group of an MP4 or MOV track: the samples that the track's
 * sample-to-group box (sbgp) of grouping type 'rap ' maps to a group description (ISO/IEC 14496-12,
 * 10.4). FFmpeg's mov demuxer gives each of these samples the keyframe flag, beside the samples
 * that the sync-sample table (stss) lists. An open-GOP HEVC file can list only its first IDR
 * picture in the stss and mark its CRA pictures in this group.
 *
 * <p>The members follow the demuxer's reading. A sample belongs to the group when its entry's group
 * description index is not 0, and the demuxer reads no group description (sgpd) for this grouping
 * type. The last 'rap ' box with entries replaces any earlier one, and only a version 1 box carries
 * a grouping type parameter. Samples after the last entry belong to no group.
 */
final class Mp4RandomAccessGroup {

  private static final String GROUPING_TYPE = "rap ";

  private Mp4RandomAccessGroup() {}

  /**
   * The group's members among the first {@code sampleCount} samples, as zero-based sample indexes
   * in decode order. A sample table without a 'rap ' sbgp has none.
   */
  static BitSet members(ByteBuffer sampleTable, int sampleCount) {
    var members = new BitSet(sampleCount);
    long start = 0;
    for (var entry : entries(sampleTable)) {
      var end = Math.min(sampleCount, start + entry.count());
      if (entry.count() == 0) {
        // The demuxer moves to the next entry only after it has counted as many samples as the
        // entry declares, so an entry that declares no samples covers every later sample.
        end = sampleCount;
      }

      if (entry.value() != 0) {
        members.set((int) start, (int) end);
      }

      start = end;
    }

    return members;
  }

  private static List<Run> entries(ByteBuffer sampleTable) {
    List<Run> entries = List.of();
    for (var box : children(sampleTable, "sbgp")) {
      var version = box.get() & 0xFF;
      skipFlags(box);
      if (!GROUPING_TYPE.equals(fourCharacterCode(box))) {
        continue;
      }

      if (version == 1) {
        skip(box, Integer.BYTES);
      }

      var boxEntries = runEntries(box);
      if (!boxEntries.isEmpty()) {
        entries = boxEntries;
      }
    }

    return entries;
  }
}
