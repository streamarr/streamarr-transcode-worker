package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.Mp4MovieBox.boxes;
import static com.streamarr.transcode.probe.Mp4MovieBox.child;
import static com.streamarr.transcode.probe.Mp4MovieBox.skip;
import static com.streamarr.transcode.probe.Mp4SampleTables.malformed;
import static com.streamarr.transcode.probe.Mp4SampleTables.runs;
import static com.streamarr.transcode.probe.Mp4SampleTables.timescale;
import static com.streamarr.transcode.probe.Mp4SampleTables.track;

import com.streamarr.transcode.probe.Mp4MovieBox.Box;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Optional;
import lombok.NonNull;

/**
 * Whether a stream copy delivers every sample of an MP4 or MOV track with the size that the track's
 * sample table lists (ADR 0054). A copy changes the sizes of:
 *
 * <ul>
 *   <li>H.264 and HEVC video that a copy cannot deliver without the Annex B bitstream filter, which
 *       adds parameter sets and rewrites the NAL unit lengths: {@link CopiedPacketBytes} decides it
 *       from the track's only sample entry and its avcC or hvcC record;
 *   <li>AV1, whose padding and temporal delimiter OBUs the mp4 muxer drops, and VP9, which the mp4
 *       muxer passes through its superframe filter;
 *   <li>AAC whose samples are ADTS frames, whose headers the copy's {@code aac_adtstoasc} strips: a
 *       track without an AudioSpecificConfig in its {@code esds}, or whose first sample starts with
 *       an ADTS sync word;
 *   <li>E-AC-3 whose samples hold fewer than 1,536 audio samples, which the mp4 muxer joins into
 *       samples of six audio blocks.
 * </ul>
 *
 * Other codecs reach the delivered segment as their samples are. Of the media data, the check reads
 * only the first two bytes of an AAC track's first sample.
 */
final class CopiedSampleSizes {

  private static final int VISUAL_SAMPLE_ENTRY_BYTES = 78;
  private static final int AUDIO_SAMPLE_ENTRY_BYTES = 28;
  private static final int SOUND_DESCRIPTION_VERSION_1_EXTRA_BYTES = 16;
  private static final int SOUND_DESCRIPTION_VERSION_2_EXTRA_BYTES = 36;
  private static final long E_AC_3_SAMPLES_PER_SIX_BLOCKS = 1_536;
  private static final byte ES_DESCRIPTOR_TAG = 0x03;
  private static final byte DECODER_CONFIG_DESCRIPTOR_TAG = 0x04;
  private static final byte DECODER_SPECIFIC_INFO_TAG = 0x05;
  private static final int DECODER_CONFIG_DESCRIPTOR_FIXED_BYTES = 13;
  private static final int STREAM_DEPENDENCE_FLAG = 0x80;
  private static final int URL_FLAG = 0x40;
  private static final int OCR_STREAM_FLAG = 0x20;

  private CopiedSampleSizes() {}

  /**
   * Whether the copy keeps the sizes of the stream's samples. {@code stream} names the codec and
   * the audio sample rate that ffprobe gives the stream.
   *
   * @throws ContainerIndexException when the track's boxes are malformed
   */
  static boolean areKept(SourceMovie source, Mp4StreamMeasurer.TrackStream stream)
      throws IOException {
    var media =
        child(track(source.movie(), stream.trackId()), "mdia").orElseThrow(() -> malformed("mdia"));
    var sampleTable =
        child(media, "minf")
            .flatMap(info -> child(info, "stbl"))
            .orElseThrow(() -> malformed("stbl"));
    return switch (stream.codecName()) {
      case "h264" ->
          sampleEntry(sampleTable)
              .filter(entry -> keepsPacketBytes(entry, ProbedCodecFamily.H264, "avcC"))
              .isPresent();
      case "hevc" ->
          sampleEntry(sampleTable)
              .filter(entry -> keepsPacketBytes(entry, ProbedCodecFamily.HEVC, "hvcC"))
              .isPresent();
      case "av1", "vp9" -> false;
      case "aac" ->
          sampleEntry(sampleTable).filter(CopiedSampleSizes::hasAudioSpecificConfig).isPresent()
              && !startsWithAdtsSyncWord(source.channel(), sampleTable);
      case "eac3" -> holdsSixBlocksPerSample(media, sampleTable, stream.sampleRate());
      default -> true;
    };
  }

  /** The movie box of an MP4 or MOV file and the file it came from. */
  record SourceMovie(@NonNull ByteBuffer movie, @NonNull FileChannel channel) {}

  /** The track's only sample entry; a track with several takes the copy's first for all. */
  private static Optional<Box> sampleEntry(ByteBuffer sampleTable) {
    var descriptions = child(sampleTable, "stsd").orElseThrow(() -> malformed("stsd"));
    skip(descriptions, 4);
    if (descriptions.getInt() != 1) {
      return Optional.empty();
    }

    return boxes(descriptions.slice()).stream().findFirst();
  }

  /**
   * Whether a copy without the Annex B filter keeps the samples, by the same rule that decides the
   * probe's copy fact. The entry's avcC or hvcC box holds the record that ffprobe gives as the
   * stream's extradata.
   */
  private static boolean keepsPacketBytes(Box entry, ProbedCodecFamily family, String recordBox) {
    return child(children(entry, VISUAL_SAMPLE_ENTRY_BYTES), recordBox)
        .map(
            record -> {
              var bytes = new byte[record.remaining()];
              record.duplicate().get(bytes);
              return bytes;
            })
        .filter(
            record ->
                record.length > 0
                    && CopiedPacketBytes.areKept(family, Optional.of(entry.type()), record))
        .isPresent();
  }

  /**
   * An MP4 {@code mp4a} entry holds its AudioSpecificConfig as the DecoderSpecificInfo of the
   * DecoderConfigDescriptor in its {@code esds} box, which a QuickTime entry can wrap in a {@code
   * wave} box.
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
    return child(boxes, "esds")
        .or(() -> child(boxes, "wave").flatMap(wave -> child(wave, "esds")))
        .filter(CopiedSampleSizes::holdsDecoderSpecificInfo)
        .isPresent();
  }

  // ISO/IEC 14496-1: an ES_Descriptor, whose flags announce optional fields, then a
  // DecoderConfigDescriptor of 13 fixed bytes, then the DecoderSpecificInfo.
  private static boolean holdsDecoderSpecificInfo(ByteBuffer esds) {
    skip(esds, 4);
    if (esds.get() != ES_DESCRIPTOR_TAG) {
      return false;
    }

    descriptorLength(esds);
    skip(esds, 2);
    var flags = esds.get() & 0xFF;
    if ((flags & STREAM_DEPENDENCE_FLAG) != 0) {
      skip(esds, 2);
    }

    if ((flags & URL_FLAG) != 0) {
      skip(esds, esds.get() & 0xFF);
    }

    if ((flags & OCR_STREAM_FLAG) != 0) {
      skip(esds, 2);
    }

    if (esds.get() != DECODER_CONFIG_DESCRIPTOR_TAG) {
      return false;
    }

    descriptorLength(esds);
    skip(esds, DECODER_CONFIG_DESCRIPTOR_FIXED_BYTES);
    return esds.get() == DECODER_SPECIFIC_INFO_TAG && descriptorLength(esds) > 0;
  }

  // Each byte gives 7 bits of the length, and its top bit says whether another byte follows.
  private static int descriptorLength(ByteBuffer descriptor) {
    var length = 0;
    for (var i = 0; i < 4; i++) {
      var next = descriptor.get() & 0xFF;
      length = (length << 7) | (next & 0x7F);
      if ((next & 0x80) == 0) {
        break;
      }
    }

    return length;
  }

  private static boolean startsWithAdtsSyncWord(FileChannel channel, ByteBuffer sampleTable)
      throws IOException {
    var firstChunk = firstChunkOffset(sampleTable);
    var head = ContainerFileBytes.read(channel, firstChunk, 2);
    return (head.get(0) & 0xFF) == 0xFF && (head.get(1) & 0xF0) == 0xF0;
  }

  private static long firstChunkOffset(ByteBuffer sampleTable) {
    var offsets = child(sampleTable, "stco");
    var isLarge = offsets.isEmpty();
    var table = offsets.or(() -> child(sampleTable, "co64")).orElseThrow(() -> malformed("stco"));
    skip(table, 4);
    if (table.getInt() == 0) {
      throw malformed("no chunk");
    }

    if (!isLarge) {
      return Integer.toUnsignedLong(table.getInt());
    }

    var offset = table.getLong();
    if (offset < 0) {
      throw malformed("chunk offset beyond a file position");
    }

    return offset;
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
