#!/usr/bin/env python3
"""Moves each Dolby Vision RPU of a raw HEVC stream ahead of a picture.

    move-rpus-ahead.py next <source> <target>
    move-rpus-ahead.py own <source> <target>

A Dolby Vision RPU (NAL unit type 62) belongs after its picture, as the last NAL unit of the
picture's sample. FFmpeg's HEVC parser starts a sample at a VPS but never at an RPU, so the target
puts a copy of the stream's first 3 NAL units, its VPS, SPS and PPS, ahead of each picture that
does not start with them, and the RPU between them and the picture's first slice:

- next: each RPU starts the next picture's sample, as mkvmerge wrote it before version 57. The last
  RPU has no next picture, so the target leaves it out.
- own: each RPU starts its own picture's sample, a layout that no known muxer writes but that
  FFmpeg's remuxing keeps.

FFmpeg's HEVC decoder ignores an RPU that is not the last NAL unit of its sample, so the target's
frames carry no RPU and only its packets do.
"""
import sys

mode, source, target = sys.argv[1], sys.argv[2], sys.argv[3]
if mode not in ("next", "own"):
    raise SystemExit("the mode is next or own")
data = open(source, "rb").read()
starts = []
position = 0
while position + 2 < len(data):
    if data[position] == 0 and data[position + 1] == 0 and data[position + 2] == 1:
        starts.append(position + 3)
        position += 3
    else:
        position += 1
units = []
for index, start in enumerate(starts):
    end = starts[index + 1] - 3 if index + 1 < len(starts) else len(data)
    units.append(data[start:end].rstrip(b"\x00"))


def unit_type(unit):
    return (unit[0] >> 1) & 0x3F


def is_slice(unit):
    return unit_type(unit) < 32


parameter_sets = units[:3]
if [unit_type(unit) for unit in parameter_sets] != [32, 33, 34]:
    raise SystemExit("the stream does not start with a VPS, an SPS and a PPS")


def ahead_of_picture(picture, rpu):
    """The picture's NAL units with the RPU ahead of its first slice, behind parameter sets."""
    first_slice = next(index for index, unit in enumerate(picture) if is_slice(unit))
    prefix = picture[:first_slice]
    if not prefix or unit_type(prefix[0]) != 32:
        prefix = parameter_sets + prefix
    return prefix + [rpu] + picture[first_slice:]


moved = []
if mode == "next":
    pending = None
    for unit in units:
        if unit_type(unit) == 62:
            pending = unit
            continue
        if is_slice(unit) and pending is not None:
            moved += parameter_sets + [pending]
            pending = None
        moved.append(unit)
else:
    # A picture's NAL units run up to its RPU, which follows its slices.
    picture = []
    for unit in units:
        if unit_type(unit) != 62:
            picture.append(unit)
            continue
        moved += ahead_of_picture(picture, unit)
        picture = []
    moved += picture
open(target, "wb").write(b"".join(b"\x00\x00\x00\x01" + unit for unit in moved))
