#!/usr/bin/env python3
"""Moves each Dolby Vision RPU of a raw HEVC stream ahead of the next picture.

    move-rpus-ahead.py <source> <target>

A Dolby Vision RPU (NAL unit type 62) belongs after its picture, as the last NAL unit of the
picture's sample. The target holds each RPU ahead of the next picture's first slice instead, behind
a copy of the stream's first 3 NAL units, its VPS, SPS and PPS, so that FFmpeg's HEVC parser starts
the next sample there. The last RPU has no next picture, so the target leaves it out. FFmpeg's HEVC
decoder ignores an RPU that is not the last NAL unit of its sample, so the target's frames carry no
RPU and only its packets do.
"""
import sys

source, target = sys.argv[1], sys.argv[2]
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


parameter_sets = units[:3]
if [unit_type(unit) for unit in parameter_sets] != [32, 33, 34]:
    raise SystemExit("the stream does not start with a VPS, an SPS and a PPS")
moved = []
pending = None
for unit in units:
    if unit_type(unit) == 62:
        pending = unit
        continue
    if unit_type(unit) < 32 and pending is not None:
        moved += parameter_sets + [pending]
        pending = None
    moved.append(unit)
open(target, "wb").write(b"".join(b"\x00\x00\x00\x01" + unit for unit in moved))
