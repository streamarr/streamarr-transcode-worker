package com.streamarr.transcode.fixtures;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * Reads every sample of a fragmented MP4 output, independently of the worker's reader: its track,
 * decode and presentation times, duration, size, flags and bytes. Placement follows ISO/IEC
 * 14496-12 (8.8.7, 8.8.8); the recordings and the producer's output use nothing else.
 */
public final class Mp4Samples {

  private static final int NON_SYNC_SAMPLE = 0x0001_0000;

  private Mp4Samples() {}

  /** Reads an output that begins with its initialization segment. */
  public static Movie read(byte[] output) {
    var reader = new Reader(ByteBuffer.wrap(output));
    reader.readAll();
    return new Movie(Map.copyOf(reader.tracks), List.copyOf(reader.fragments));
  }

  /** A track the {@code moov} declares. */
  public record Track(long trackId, String handler, long timescale) {

    public boolean isVideo() {
      return handler.equals("vide");
    }

    public boolean isAudio() {
      return handler.equals("soun");
    }
  }

  /**
   * One sample. Its bytes are a read-only view, so two samples are equal when their fields and
   * bytes are.
   */
  @Builder
  public record Sample(
      long trackId,
      long decodeTime,
      long presentationTime,
      long duration,
      long size,
      int flags,
      ByteBuffer data) {

    public boolean isSync() {
      return (flags & NON_SYNC_SAMPLE) == 0;
    }

    public long end() {
      return decodeTime + duration;
    }
  }

  /** One {@code moof} and its {@code mdat}, with their samples in {@code traf} order. */
  public record MovieFragment(List<Sample> samples) {}

  /** The tracks and fragments of an output. */
  public record Movie(Map<Long, Track> tracks, List<MovieFragment> fragments) {

    public Track videoTrack() {
      return tracks.values().stream().filter(Track::isVideo).findFirst().orElseThrow();
    }

    public Track audioTrack() {
      return tracks.values().stream().filter(Track::isAudio).findFirst().orElseThrow();
    }

    public boolean hasAudio() {
      return tracks.values().stream().anyMatch(Track::isAudio);
    }

    /** Every sample of the track, in decode order. */
    public List<Sample> samplesOf(Track track) {
      return fragments.stream()
          .flatMap(fragment -> fragment.samples().stream())
          .filter(sample -> sample.trackId() == track.trackId())
          .toList();
    }
  }

  private record Defaults(long duration, long size, int flags) {}

  private static final class Reader {

    private final ByteBuffer output;
    private final Map<Long, Track> tracks = new LinkedHashMap<>();
    private final Map<Long, Defaults> trackDefaults = new HashMap<>();
    private final List<MovieFragment> fragments = new ArrayList<>();

    private Reader(ByteBuffer output) {
      this.output = output;
    }

    private void readAll() {
      var position = 0;
      var moofStart = -1;
      while (position < output.limit()) {
        var size = output.getInt(position);
        var type = typeAt(position);
        if (type.equals("moov")) {
          readMoov(position + 8, position + size);
        }

        if (type.equals("moof")) {
          moofStart = position;
        }

        if (type.equals("mdat")) {
          fragments.add(new MovieFragment(readMoof(moofStart)));
        }

        position += size;
      }
    }

    private void readMoov(int start, int end) {
      for (var trak : children(start, end, "trak")) {
        var mdia = child(trak, "mdia");
        var track =
            new Track(
                headerField(child(trak, "tkhd")),
                typeAt(child(mdia, "hdlr") + 12),
                headerField(child(mdia, "mdhd")));
        tracks.put(track.trackId(), track);
      }

      for (var mvex : children(start, end, "mvex")) {
        for (var trex : children(mvex + 8, mvex + output.getInt(mvex), "trex")) {
          trackDefaults.put(
              unsigned(trex + 12),
              new Defaults(unsigned(trex + 20), unsigned(trex + 24), output.getInt(trex + 28)));
        }
      }
    }

    // A tkhd's track id or an mdhd's timescale: the field after the creation and modification
    // times, whose width follows the box's version.
    private long headerField(int box) {
      var times = output.get(box + 8) == 1 ? 16 : 8;
      return unsigned(box + 12 + times);
    }

    private List<Sample> readMoof(int moof) {
      var samples = new ArrayList<Sample>();
      var previousTrafEnd = (long) moof;
      for (var traf : children(moof + 8, moof + output.getInt(moof), "traf")) {
        previousTrafEnd = readTraf(new TrafContext(moof, traf, previousTrafEnd), samples);
      }

      return samples;
    }

    private record TrafContext(int moof, int traf, long previousTrafEnd) {}

    private long readTraf(TrafContext context, List<Sample> samples) {
      var tfhd = child(context.traf(), "tfhd");
      var tfhdFlags = output.getInt(tfhd + 8) & 0xFF_FFFF;
      var trackId = unsigned(tfhd + 12);
      var field = tfhd + 16;
      var base = context.previousTrafEnd();
      if ((tfhdFlags & 0x02_0000) != 0) {
        base = context.moof();
      }

      if ((tfhdFlags & 0x1) != 0) {
        base = output.getLong(field);
        field += 8;
      }

      field += (tfhdFlags & 0x2) != 0 ? 4 : 0;
      var trex = trackDefaults.getOrDefault(trackId, new Defaults(0, 0, 0));
      var duration = trex.duration();
      var size = trex.size();
      var flags = trex.flags();
      if ((tfhdFlags & 0x8) != 0) {
        duration = unsigned(field);
        field += 4;
      }

      if ((tfhdFlags & 0x10) != 0) {
        size = unsigned(field);
        field += 4;
      }

      if ((tfhdFlags & 0x20) != 0) {
        flags = output.getInt(field);
      }

      var tfdt = child(context.traf(), "tfdt");
      var decodeTime = output.get(tfdt + 8) == 1 ? output.getLong(tfdt + 12) : unsigned(tfdt + 12);
      var cursor =
          new RunCursor(
              TrafHeader.builder()
                  .trackId(trackId)
                  .defaults(new Defaults(duration, size, flags))
                  .decodeTime(decodeTime)
                  .base(base)
                  .build());
      var trafEnd = context.traf() + output.getInt(context.traf());
      for (var trun : children(context.traf() + 8, trafEnd, "trun")) {
        readTrun(trun, cursor, samples);
      }

      return cursor.dataPosition;
    }

    @Builder
    private record TrafHeader(long trackId, Defaults defaults, long decodeTime, long base) {}

    // Where the next sample of a traf starts, in decode time and in the output.
    private static final class RunCursor {
      private final TrafHeader header;
      private long decodeTime;
      private long dataPosition;

      private RunCursor(TrafHeader header) {
        this.header = header;
        this.decodeTime = header.decodeTime();
        this.dataPosition = header.base();
      }
    }

    private void readTrun(int trun, RunCursor run, List<Sample> samples) {
      var version = output.get(trun + 8);
      var flags = output.getInt(trun + 8) & 0xFF_FFFF;
      var count = unsigned(trun + 12);
      var field = trun + 16;
      if ((flags & 0x1) != 0) {
        run.dataPosition = run.header.base() + output.getInt(field);
        field += 4;
      }

      var firstSampleFlags = run.header.defaults().flags();
      var hasFirstSampleFlags = (flags & 0x4) != 0;
      if (hasFirstSampleFlags) {
        firstSampleFlags = output.getInt(field);
        field += 4;
      }

      for (var index = 0; index < count; index++) {
        var duration = run.header.defaults().duration();
        var size = run.header.defaults().size();
        var sampleFlags = run.header.defaults().flags();
        var compositionOffset = 0L;
        if ((flags & 0x100) != 0) {
          duration = unsigned(field);
          field += 4;
        }

        if ((flags & 0x200) != 0) {
          size = unsigned(field);
          field += 4;
        }

        if ((flags & 0x400) != 0) {
          sampleFlags = output.getInt(field);
          field += 4;
        }

        if ((flags & 0x800) != 0) {
          compositionOffset = version == 1 ? output.getInt(field) : unsigned(field);
          field += 4;
        }

        if (index == 0 && hasFirstSampleFlags) {
          sampleFlags = firstSampleFlags;
        }

        var data =
            output
                .slice(Math.toIntExact(run.dataPosition), Math.toIntExact(size))
                .asReadOnlyBuffer();
        samples.add(
            Sample.builder()
                .trackId(run.header.trackId())
                .decodeTime(run.decodeTime)
                .presentationTime(run.decodeTime + compositionOffset)
                .duration(duration)
                .size(size)
                .flags(sampleFlags)
                .data(data)
                .build());
        run.decodeTime += duration;
        run.dataPosition += size;
      }
    }

    private int child(int box, String type) {
      return children(box + headerLength(box), box + output.getInt(box), type).getFirst();
    }

    // Full boxes carry version and flags after the header; containers do not.
    private int headerLength(int box) {
      return switch (typeAt(box)) {
        case "tkhd", "mdhd", "hdlr", "tfhd", "tfdt", "trun", "trex" -> 12;
        default -> 8;
      };
    }

    private List<Integer> children(int start, int end, String type) {
      var found = new ArrayList<Integer>();
      var position = start;
      while (position < end) {
        if (typeAt(position).equals(type)) {
          found.add(position);
        }

        position += output.getInt(position);
      }

      return found;
    }

    private String typeAt(int position) {
      var type = new byte[4];
      output.get(position + 4, type);
      return new String(type, StandardCharsets.ISO_8859_1);
    }

    private long unsigned(int position) {
      return Integer.toUnsignedLong(output.getInt(position));
    }
  }
}
