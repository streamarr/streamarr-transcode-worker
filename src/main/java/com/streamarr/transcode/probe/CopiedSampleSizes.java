package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.Mp4MovieBox.boxes;
import static com.streamarr.transcode.probe.Mp4MovieBox.child;
import static com.streamarr.transcode.probe.Mp4MovieBox.skip;
import static com.streamarr.transcode.probe.Mp4SampleTables.malformed;
import static com.streamarr.transcode.probe.Mp4SampleTables.runs;
import static com.streamarr.transcode.probe.Mp4SampleTables.timescale;
import static com.streamarr.transcode.probe.Mp4SampleTables.track;

import com.streamarr.transcode.probe.Mp4MovieBox.Box;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a stream copy delivers every sample of an MP4 or MOV track with the size that the track's
 * sample table lists (ADR 0054). A copy changes the sizes of:
 *
 * <ul>
 *   <li>H.264 and HEVC video that keeps its parameter sets in its samples ({@code avc3}, {@code
 *       hev1}, {@code dvav}, {@code dvhe}), or whose decoder configuration record is missing, holds
 *       no parameter sets or prefixes NAL units with other than 4 bytes: the copy runs the Annex B
 *       bitstream filter, which adds parameter sets and rewrites the NAL unit lengths;
 *   <li>AAC without an AudioSpecificConfig, whose samples are ADTS frames that the copy strips;
 *   <li>E-AC-3 whose samples hold fewer than 1,536 audio samples, which the mp4 muxer joins into
 *       samples of six audio blocks.
 * </ul>
 *
 * Other codecs reach the delivered segment as their samples are.
 */
final class CopiedSampleSizes {

  private static final Set<String> RECORD_AVC_ENTRIES = Set.of("avc1", "dva1");
  private static final Set<String> RECORD_HEVC_ENTRIES = Set.of("hvc1", "dvh1");
  private static final int NAL_LENGTH_BYTES = 4;
  private static final int VISUAL_SAMPLE_ENTRY_BYTES = 78;
  private static final int AUDIO_SAMPLE_ENTRY_BYTES = 28;
  private static final int SOUND_DESCRIPTION_VERSION_1_EXTRA_BYTES = 16;
  private static final int SOUND_DESCRIPTION_VERSION_2_EXTRA_BYTES = 36;
  private static final int HEVC_VPS = 32;
  private static final int HEVC_SPS = 33;
  private static final int HEVC_PPS = 34;
  private static final int HVCC_LENGTH_SIZE_OFFSET = 21;
  private static final long E_AC_3_SAMPLES_PER_SIX_BLOCKS = 1_536;

  private CopiedSampleSizes() {}

  /**
   * Whether the copy keeps the sizes of the stream's samples. {@code stream} names the codec and
   * the audio sample rate that ffprobe gives the stream.
   *
   * @throws ContainerIndexException when the track's boxes are malformed
   */
  static boolean areKept(ByteBuffer movie, Mp4StreamMeasurer.TrackStream stream) {
    var media = child(track(movie, stream.trackId()), "mdia").orElseThrow(() -> malformed("mdia"));
    var sampleTable =
        child(media, "minf")
            .flatMap(info -> child(info, "stbl"))
            .orElseThrow(() -> malformed("stbl"));
    return switch (stream.codecName()) {
      case "h264" -> sampleEntry(sampleTable).filter(CopiedSampleSizes::isRecordAvc).isPresent();
      case "hevc" -> sampleEntry(sampleTable).filter(CopiedSampleSizes::isRecordHevc).isPresent();
      case "aac" ->
          sampleEntry(sampleTable).filter(CopiedSampleSizes::hasAudioSpecificConfig).isPresent();
      case "eac3" -> holdsSixBlocksPerSample(media, sampleTable, stream.sampleRate());
      default -> true;
    };
  }

  /** The track's only sample entry; a track with several takes the copy's first for all. */
  private static Optional<Box> sampleEntry(ByteBuffer sampleTable) {
    var descriptions = child(sampleTable, "stsd").orElseThrow(() -> malformed("stsd"));
    skip(descriptions, 4);
    if (descriptions.getInt() != 1) {
      return Optional.empty();
    }

    return boxes(descriptions.slice()).stream().findFirst();
  }

  private static boolean isRecordAvc(Box entry) {
    if (!RECORD_AVC_ENTRIES.contains(entry.type())) {
      return false;
    }

    var record = child(children(entry, VISUAL_SAMPLE_ENTRY_BYTES), "avcC");
    if (record.isEmpty()) {
      return false;
    }

    var avcC = record.get();
    skip(avcC, 4);
    var lengthSize = (avcC.get() & 0x03) + 1;
    var sequenceParameterSets = avcC.get() & 0x1F;
    for (var i = 0; i < sequenceParameterSets; i++) {
      skip(avcC, avcC.getShort() & 0xFFFF);
    }

    var pictureParameterSets = avcC.get() & 0xFF;
    return lengthSize == NAL_LENGTH_BYTES && sequenceParameterSets > 0 && pictureParameterSets > 0;
  }

  private static boolean isRecordHevc(Box entry) {
    if (!RECORD_HEVC_ENTRIES.contains(entry.type())) {
      return false;
    }

    var record = child(children(entry, VISUAL_SAMPLE_ENTRY_BYTES), "hvcC");
    if (record.isEmpty()) {
      return false;
    }

    var hvcC = record.get();
    skip(hvcC, HVCC_LENGTH_SIZE_OFFSET);
    var lengthSize = (hvcC.get() & 0x03) + 1;
    var arrays = hvcC.get() & 0xFF;
    var heldTypes = new HashSet<Integer>();
    for (var i = 0; i < arrays; i++) {
      var type = hvcC.get() & 0x3F;
      var units = hvcC.getShort() & 0xFFFF;
      for (var unit = 0; unit < units; unit++) {
        skip(hvcC, hvcC.getShort() & 0xFFFF);
      }

      if (units > 0) {
        heldTypes.add(type);
      }
    }

    return lengthSize == NAL_LENGTH_BYTES
        && heldTypes.containsAll(Set.of(HEVC_VPS, HEVC_SPS, HEVC_PPS));
  }

  /**
   * An MP4 {@code mp4a} entry holds its AudioSpecificConfig in an {@code esds} box, which a
   * QuickTime entry can wrap in a {@code wave} box.
   */
  private static boolean hasAudioSpecificConfig(Box entry) {
    if (!"mp4a".equals(entry.type())) {
      return false;
    }

    var fields = entry.payload().duplicate();
    skip(fields, 8);
    var extraBytes =
        switch (fields.getShort() & 0xFFFF) {
          case 0 -> 0;
          case 1 -> SOUND_DESCRIPTION_VERSION_1_EXTRA_BYTES;
          case 2 -> SOUND_DESCRIPTION_VERSION_2_EXTRA_BYTES;
          default -> -1;
        };
    if (extraBytes < 0) {
      return false;
    }

    var boxes = children(entry, AUDIO_SAMPLE_ENTRY_BYTES + extraBytes);
    return child(boxes, "esds").isPresent()
        || child(boxes, "wave").flatMap(wave -> child(wave, "esds")).isPresent();
  }

  private static boolean holdsSixBlocksPerSample(
      ByteBuffer media, ByteBuffer sampleTable, long sampleRate) {
    if (sampleRate <= 0) {
      return false;
    }

    var timescale = timescale(child(media, "mdhd").orElseThrow(() -> malformed("mdhd")));
    var sixBlocks = Math.multiplyExact(E_AC_3_SAMPLES_PER_SIX_BLOCKS, timescale);
    return runs(child(sampleTable, "stts").orElseThrow(() -> malformed("stts"))).stream()
        .allMatch(run -> Math.multiplyExact((long) run.value(), sampleRate) == sixBlocks);
  }

  private static ByteBuffer children(Box entry, int fieldBytes) {
    var payload = entry.payload().duplicate();
    skip(payload, fieldBytes);
    return payload.slice();
  }
}
