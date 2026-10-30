#!/usr/bin/env python3
"""Empties the hvcC record of a Matroska file's HEVC stream, so that only its samples hold the
parameter sets.

    empty-hvcc-arrays.py <source> <target>

The target keeps the record's 22 header bytes, whose general profile, tier and level stay, and
gives it no NAL unit arrays. An EBML Void element fills the bytes that the arrays held, so that
every element after the CodecPrivate element keeps its offset and the SeekHead and Cues stay valid.
The source's encoder must repeat its parameter sets in its keyframes.
"""
import sys

CODEC_PRIVATE = b"\x63\xa2"
VOID = b"\xec"
HEADER_BYTES = 22


def size_at(data, at):
    """The value and byte count of the EBML size that starts at the offset."""
    first = data[at]
    width = 1
    while not first & (0x80 >> (width - 1)):
        width += 1
    value = first & ((0x80 >> (width - 1)) - 1)
    for index in range(1, width):
        value = (value << 8) | data[at + index]
    return value, width


def encoded_size(value, width):
    return ((1 << (7 * width)) | value).to_bytes(width, "big")


source, target = sys.argv[1], sys.argv[2]
data = bytearray(open(source, "rb").read())
element = data.find(CODEC_PRIVATE)
if element < 0:
    raise SystemExit("no CodecPrivate element")
size, width = size_at(data, element + len(CODEC_PRIVATE))
body = element + len(CODEC_PRIVATE) + width
if data[body] != 1 or size <= HEADER_BYTES + 1:
    raise SystemExit("the CodecPrivate element holds no hvcC record with arrays")
record = bytes(data[body : body + HEADER_BYTES]) + b"\x00"
freed = size - len(record)
void_width = 1 if freed - 2 < 0x7F else 2
filler = freed - len(VOID) - void_width
replacement = (
    CODEC_PRIVATE
    + encoded_size(len(record), width)
    + record
    + VOID
    + encoded_size(filler, void_width)
    + bytes(filler)
)
data[element : body + size] = replacement
open(target, "wb").write(data)
