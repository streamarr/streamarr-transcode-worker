#!/usr/bin/env python3
"""Puts a Dolby Vision RPU after the slices of each picture of a raw HEVC stream, ahead of the
suffix SEI NAL unit that follows them.

    insert-rpus-before-suffix-sei.py <rpus> <source> <target>

<rpus> is the RPU file that `dovi_tool generate` writes: one RPU per picture, each behind a start
code and starting with the RPU prefix 0x19. HEVC carries each RPU in a NAL unit of type 62 on layer
0 with TemporalId 0 (header 0x7c01). The source must follow the slices of each picture with a
suffix SEI NAL unit (type 40), as x265 writes its decoded picture hash, and hold no more pictures
than the file holds RPUs. Every sample of the target then ends with the RPU and that suffix SEI
NAL unit, in that order, so each RPU follows a slice of its own sample but does not end it.
FFmpeg's HEVC decoder reads an RPU only from the last NAL unit of a packet, so the target's frames
carry no RPU and only its packets do.
"""
import sys


def units(data):
    starts = []
    position = 0
    while position + 2 < len(data):
        if data[position] == 0 and data[position + 1] == 0 and data[position + 2] == 1:
            starts.append(position + 3)
            position += 3
        else:
            position += 1
    for index, start in enumerate(starts):
        end = starts[index + 1] - 3 if index + 1 < len(starts) else len(data)
        yield data[start:end].rstrip(b"\x00")


def unit_type(unit):
    return (unit[0] >> 1) & 0x3F


def is_slice(unit):
    return unit_type(unit) < 32


def starts_picture(unit):
    """A slice whose first_slice_segment_in_pic_flag is set starts a picture."""
    return is_slice(unit) and unit[2] & 0x80


rpus = [unit for unit in units(open(sys.argv[1], "rb").read()) if unit[:1] == b"\x19"]
source = list(units(open(sys.argv[2], "rb").read()))
output = []
pictures = 0
for index, unit in enumerate(source):
    output.append(unit)
    following = source[index + 1] if index + 1 < len(source) else None
    if not is_slice(unit) or (following is not None and is_slice(following)
                              and not starts_picture(following)):
        continue
    if following is None or unit_type(following) != 40:
        raise SystemExit("a picture's slices are not followed by a suffix SEI NAL unit")
    if pictures == len(rpus):
        raise SystemExit("the stream holds more pictures than the file holds RPUs")
    output.append(b"\x7c\x01" + rpus[pictures])
    pictures += 1
if pictures == 0:
    raise SystemExit("the stream holds no picture")
open(sys.argv[3], "wb").write(b"".join(b"\x00\x00\x00\x01" + unit for unit in output))
