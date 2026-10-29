#!/usr/bin/env python3
"""Puts a Dolby Vision RPU after each picture of a raw H.264 stream, as profile 9 carries them.

    insert-avc-rpus.py <rpus> <source> <target>

<rpus> is the RPU file that `dovi_tool generate` writes: one RPU per picture, each behind a start
code and starting with the RPU prefix 0x19. Profile 9 carries each RPU in an H.264 NAL unit of
type 28 (header 0x1c) after the slice of its own picture. The source must hold one slice per
picture, as x264 writes without sliced threads, and no more pictures than the file holds RPUs.
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


rpus = [unit for unit in units(open(sys.argv[1], "rb").read()) if unit[:1] == b"\x19"]
output = []
pictures = 0
for unit in units(open(sys.argv[2], "rb").read()):
    output.append(unit)
    if unit[0] & 0x1F in (1, 5):
        if pictures == len(rpus):
            raise SystemExit("the stream holds more pictures than the file holds RPUs")
        output.append(b"\x1c" + rpus[pictures])
        pictures += 1
if pictures == 0:
    raise SystemExit("the stream holds no picture")
open(sys.argv[3], "wb").write(b"".join(b"\x00\x00\x00\x01" + unit for unit in output))
