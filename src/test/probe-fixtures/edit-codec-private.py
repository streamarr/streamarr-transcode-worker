#!/usr/bin/env python3
"""Edits the decoder configuration record in the CodecPrivate element of a Matroska file's video
stream.

    edit-codec-private.py hvcc-without-arrays <source> <target>

hvcc-without-arrays keeps the hvcC record's 22 header bytes, whose general profile, tier and level
stay, and gives it no NAL unit arrays, so that only the samples hold the parameter sets. The
source's encoder must repeat its parameter sets in its keyframes.

An EBML Void element fills the bytes that the edit frees, so that every element after the
CodecPrivate element keeps its offset and the SeekHead and Cues stay valid.
"""
import sys

CODEC_PRIVATE = b"\x63\xa2"
VOID = b"\xec"
HVCC_HEADER_BYTES = 22


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


def hvcc_without_arrays(record):
    if record[0] != 1 or len(record) <= HVCC_HEADER_BYTES + 1:
        raise SystemExit("the CodecPrivate element holds no hvcC record with arrays")
    return record[:HVCC_HEADER_BYTES] + b"\x00"


EDITS = {"hvcc-without-arrays": hvcc_without_arrays}

edit, source, target = EDITS[sys.argv[1]], sys.argv[2], sys.argv[3]
data = bytearray(open(source, "rb").read())
element = data.find(CODEC_PRIVATE)
if element < 0:
    raise SystemExit("no CodecPrivate element")
size, width = size_at(data, element + len(CODEC_PRIVATE))
body = element + len(CODEC_PRIVATE) + width
record = edit(bytes(data[body : body + size]))
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
