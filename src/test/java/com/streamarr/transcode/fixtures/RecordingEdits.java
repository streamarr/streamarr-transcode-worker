package com.streamarr.transcode.fixtures;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.streamarr.transcode.fixtures.TopLevelBoxes.Box;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Changes a recorded FFmpeg output in the ways that worker tests need. */
public final class RecordingEdits {

  private RecordingEdits() {}

  /**
   * The recording with these bytes appended to the first video sample of its first fragment. The
   * sample's size, the mdat's size and the data offsets of the runs after the video run grow to
   * match.
   */
  public static byte[] withBytesAfterTheFirstVideoSample(byte[] recording, byte[] appended) {
    var growth = appended.length;
    var boxes = TopLevelBoxes.of(recording);
    var moof = boxes.stream().filter(box -> box.type().equals("moof")).findFirst().orElseThrow();
    var mdat = boxes.get(boxes.indexOf(moof) + 1);
    var fields = ByteBuffer.wrap(recording.clone());
    var runs = runsOf(fields, moof);
    var videoRun = runs.getFirst();
    var firstSampleEnd =
        moof.start() + fields.getInt(videoRun + 16) + firstSampleSize(fields, videoRun);
    fields.putInt(
        sizeFieldOfFirstSample(fields, videoRun), firstSampleSize(fields, videoRun) + growth);
    runs.subList(1, runs.size())
        .forEach(run -> fields.putInt(run + 16, fields.getInt(run + 16) + growth));
    fields.putInt(mdat.start(), mdat.size() + growth);
    var grown = new ByteArrayOutputStream();
    grown.write(fields.array(), 0, firstSampleEnd);
    grown.writeBytes(appended);
    grown.write(fields.array(), firstSampleEnd, recording.length - firstSampleEnd);
    return grown.toByteArray();
  }

  // Where each trun of the moof starts, in traf order: the video traf's first, as FFmpeg writes it.
  private static List<Integer> runsOf(ByteBuffer fields, Box moof) {
    return childrenOf(fields, moof.start(), "traf").stream()
        .flatMap(traf -> childrenOf(fields, traf, "trun").stream())
        .toList();
  }

  // Where each child box of this type starts inside the box that starts here.
  private static List<Integer> childrenOf(ByteBuffer fields, int box, String type) {
    var children = new ArrayList<Integer>();
    var fourcc = ByteBuffer.wrap(type.getBytes(US_ASCII)).getInt();
    for (var child = box + 8; child < box + fields.getInt(box); child += fields.getInt(child)) {
      if (fields.getInt(child + 4) == fourcc) {
        children.add(child);
      }
    }

    return children;
  }

  // A trun's first sample entry follows its data offset and first-sample flags; the recordings'
  // video runs carry each sample's size, after its duration when they carry durations.
  private static int sizeFieldOfFirstSample(ByteBuffer fields, int run) {
    var flags = fields.getInt(run + 8) & 0xFF_FFFF;
    var field = run + 16;
    for (var presentField : new int[] {0x1, 0x4, 0x100}) {
      if ((flags & presentField) != 0) {
        field += 4;
      }
    }

    return field;
  }

  private static int firstSampleSize(ByteBuffer fields, int run) {
    return fields.getInt(sizeFieldOfFirstSample(fields, run));
  }
}
