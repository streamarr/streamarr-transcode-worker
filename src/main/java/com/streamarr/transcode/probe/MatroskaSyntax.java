package com.streamarr.transcode.probe;

import static java.util.Map.entry;

import java.util.Map;

/**
 * The type that FFmpeg 8.1's Matroska demuxer gives each child it reads of the master elements the
 * probe reads, from the syntax tables of {@code libavformat/matroskadec.c}. FFmpeg's EBML parse of
 * a master stops at the first child, in file order and nested, whose value its type cannot hold. It
 * skips a child of an ID the table does not list, and so does the probe; a binary child, which may
 * hold 256 MiB, never exceeds the 64 MiB the probe reads of an element, so the tables leave binary
 * children out.
 */
final class MatroskaSyntax {

  /** How FFmpeg reads a child. */
  sealed interface Element permits Value, Master {}

  /**
   * A value: an unsigned integer of at most 8 bytes, a float of 0, 4 or 8 bytes, or a string of at
   * most 16 MiB.
   */
  enum Value implements Element {
    UNSIGNED,
    FLOAT,
    STRING
  }

  /** A master element, and how FFmpeg reads each of its children by ID. */
  record Master(Map<Long, Element> children) implements Element {}

  static final Master INFO =
      new Master(
          Map.of(
              0x2AD7B1L, Value.UNSIGNED,
              0x4489L, Value.FLOAT,
              0x7BA9L, Value.STRING,
              0x4D80L, Value.STRING));

  private static final Master MASTERING_METADATA =
      new Master(
          Map.of(
              0x55D1L, Value.FLOAT,
              0x55D2L, Value.FLOAT,
              0x55D3L, Value.FLOAT,
              0x55D4L, Value.FLOAT,
              0x55D5L, Value.FLOAT,
              0x55D6L, Value.FLOAT,
              0x55D7L, Value.FLOAT,
              0x55D8L, Value.FLOAT,
              0x55D9L, Value.FLOAT,
              0x55DAL, Value.FLOAT));

  private static final Master COLOUR =
      new Master(
          Map.ofEntries(
              entry(0x55B1L, Value.UNSIGNED),
              entry(0x55B2L, Value.UNSIGNED),
              entry(0x55B3L, Value.UNSIGNED),
              entry(0x55B4L, Value.UNSIGNED),
              entry(0x55B5L, Value.UNSIGNED),
              entry(0x55B6L, Value.UNSIGNED),
              entry(0x55B7L, Value.UNSIGNED),
              entry(0x55B8L, Value.UNSIGNED),
              entry(0x55B9L, Value.UNSIGNED),
              entry(0x55BAL, Value.UNSIGNED),
              entry(0x55BBL, Value.UNSIGNED),
              entry(0x55BCL, Value.UNSIGNED),
              entry(0x55BDL, Value.UNSIGNED),
              entry(0x55D0L, MASTERING_METADATA)));

  private static final Master PROJECTION =
      new Master(
          Map.of(
              0x7671L, Value.UNSIGNED,
              0x7673L, Value.FLOAT,
              0x7674L, Value.FLOAT,
              0x7675L, Value.FLOAT));

  private static final Master VIDEO =
      new Master(
          Map.ofEntries(
              entry(0x2383E3L, Value.FLOAT),
              entry(0x54B0L, Value.UNSIGNED),
              entry(0x54BAL, Value.UNSIGNED),
              entry(0xB0L, Value.UNSIGNED),
              entry(0xBAL, Value.UNSIGNED),
              entry(0x53C0L, Value.UNSIGNED),
              entry(0x55B0L, COLOUR),
              entry(0x7670L, PROJECTION),
              entry(0x54AAL, Value.UNSIGNED),
              entry(0x54BBL, Value.UNSIGNED),
              entry(0x54CCL, Value.UNSIGNED),
              entry(0x54DDL, Value.UNSIGNED),
              entry(0x54B2L, Value.UNSIGNED),
              entry(0x9AL, Value.UNSIGNED),
              entry(0x9DL, Value.UNSIGNED),
              entry(0x53B8L, Value.UNSIGNED)));

  private static final Master AUDIO =
      new Master(
          Map.of(
              0xB5L, Value.FLOAT,
              0x78B5L, Value.FLOAT,
              0x6264L, Value.UNSIGNED,
              0x9FL, Value.UNSIGNED));

  private static final Master TRACK_OPERATION =
      new Master(
          Map.of(
              0xE3L,
              new Master(
                  Map.of(
                      0xE4L, new Master(Map.of(0xE5L, Value.UNSIGNED, 0xE6L, Value.UNSIGNED))))));

  private static final Master CONTENT_ENCODINGS =
      new Master(
          Map.of(
              0x6240L,
              new Master(
                  Map.of(
                      0x5032L,
                      Value.UNSIGNED,
                      0x5033L,
                      Value.UNSIGNED,
                      0x5034L,
                      new Master(Map.of(0x4254L, Value.UNSIGNED)),
                      0x5035L,
                      new Master(Map.of(0x47E1L, Value.UNSIGNED))))));

  private static final Master BLOCK_ADDITION_MAPPING =
      new Master(
          Map.of(
              0x41F0L, Value.UNSIGNED,
              0x41A4L, Value.STRING,
              0x41E7L, Value.UNSIGNED));

  static final Master TRACK_ENTRY =
      new Master(
          Map.ofEntries(
              entry(0xD7L, Value.UNSIGNED),
              entry(0x536EL, Value.STRING),
              entry(0x73C5L, Value.UNSIGNED),
              entry(0x83L, Value.UNSIGNED),
              entry(0x86L, Value.STRING),
              entry(0x56AAL, Value.UNSIGNED),
              entry(0x22B59CL, Value.STRING),
              entry(0x23E383L, Value.UNSIGNED),
              entry(0x23314FL, Value.FLOAT),
              entry(0x55AFL, Value.UNSIGNED),
              entry(0x88L, Value.UNSIGNED),
              entry(0x55AAL, Value.UNSIGNED),
              entry(0x55ABL, Value.UNSIGNED),
              entry(0x55ACL, Value.UNSIGNED),
              entry(0x55ADL, Value.UNSIGNED),
              entry(0x55AEL, Value.UNSIGNED),
              entry(0xE0L, VIDEO),
              entry(0xE1L, AUDIO),
              entry(0xE2L, TRACK_OPERATION),
              entry(0x6D80L, CONTENT_ENCODINGS),
              entry(0x55EEL, Value.UNSIGNED),
              entry(0x41E4L, BLOCK_ADDITION_MAPPING),
              entry(0x56BBL, Value.UNSIGNED)));

  static final Master CUE_POINT =
      new Master(
          Map.of(
              0xB3L,
              Value.UNSIGNED,
              0xB7L,
              new Master(Map.of(0xF7L, Value.UNSIGNED, 0xF1L, Value.UNSIGNED))));

  private MatroskaSyntax() {}
}
