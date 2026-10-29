#!/usr/bin/env python3
"""Gives the video stream of an AVI file other extradata.

    set-avi-extradata.py <source> <target> <hex>

The extradata follows the 40-byte BITMAPINFOHEADER in the video stream's strf chunk. The target
replaces it with the given bytes, grows biSize, the strf chunk and the lists that hold it to match,
and shrinks the JUNK chunk that FFmpeg writes ahead of the movi list by as much, so that every
chunk from the movi list on keeps its offset and the idx1 index stays valid.
"""
import struct
import sys

BITMAPINFOHEADER_SIZE = 40

source, target, extradata = sys.argv[1], sys.argv[2], bytes.fromhex(sys.argv[3])
data = bytearray(open(source, "rb").read())


def chunks(start, end):
    while start + 8 <= end:
        kind = bytes(data[start : start + 4])
        size = struct.unpack_from("<I", data, start + 4)[0]
        yield kind, start, size
        start += 8 + size + (size & 1)


def find_list(start, end, name):
    for kind, offset, size in chunks(start, end):
        if kind == b"LIST" and data[offset + 8 : offset + 12] == name:
            return offset, size
    raise SystemExit(f"no {name.decode()} list")


def video_strf(start, end):
    for kind, offset, size in chunks(start, end):
        if kind != b"LIST" or data[offset + 8 : offset + 12] != b"strl":
            continue
        inner = list(chunks(offset + 12, offset + 8 + size))
        if any(k == b"strh" and data[o + 8 : o + 12] == b"vids" for k, o, _ in inner):
            return offset, next((o, s) for k, o, s in inner if k == b"strf")
    raise SystemExit("no video stream")


hdrl, hdrl_size = find_list(12, len(data), b"hdrl")
strl, (strf, strf_size) = video_strf(hdrl + 12, hdrl + 8 + hdrl_size)
growth = BITMAPINFOHEADER_SIZE + len(extradata) - strf_size
if growth % 2:
    raise SystemExit("the extradata must keep the strf chunk an even size")
junk = next(
    (offset, size) for kind, offset, size in chunks(12, len(data)) if offset > hdrl and kind == b"JUNK"
)
if junk[1] < growth:
    raise SystemExit("the JUNK chunk ahead of the movi list is too small")

# The JUNK chunk lies after the strf chunk, so it shrinks first, while the offsets above still hold.
junk_end = junk[0] + 8 + junk[1]
del data[junk_end - growth : junk_end]
struct.pack_into("<I", data, junk[0] + 4, junk[1] - growth)
data[strf + 8 + BITMAPINFOHEADER_SIZE : strf + 8 + strf_size] = extradata
struct.pack_into("<I", data, strf + 4, BITMAPINFOHEADER_SIZE + len(extradata))
struct.pack_into("<I", data, strf + 8, BITMAPINFOHEADER_SIZE + len(extradata))
for offset in (hdrl, strl):
    struct.pack_into("<I", data, offset + 4, struct.unpack_from("<I", data, offset + 4)[0] + growth)
open(target, "wb").write(data)
