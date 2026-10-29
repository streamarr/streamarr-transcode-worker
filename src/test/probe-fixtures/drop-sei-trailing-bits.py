#!/usr/bin/env python3
"""Ends the encoder's SEI NAL unit of a raw H.264 stream without its RBSP trailing bits.

    drop-sei-trailing-bits.py <source> <target>

x264 writes its version and options as one user_data_unregistered message (payloadType 5) whose
text ends with a zero byte, followed by the RBSP trailing bits. The target drops that zero byte and
the trailing bits and shortens the message by one byte, so that the SEI NAL unit ends with the last
character of the text, as some encoders write it.
"""
import sys


def size_bytes(size):
    return b"\xff" * (size // 255) + bytes([size % 255])


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
changed = 0
for index, start in enumerate(starts):
    end = starts[index + 1] - 3 if index + 1 < len(starts) else len(data)
    unit = data[start:end].rstrip(b"\x00")
    if unit[0] & 0x1F == 6 and unit[1] == 5 and unit.endswith(b"\x00\x80"):
        position = 2
        size = 0
        while unit[position] == 0xFF:
            size += 255
            position += 1
        size += unit[position]
        payload = unit[position + 1 :]
        if len(payload) != size + 1:
            raise SystemExit("the SEI NAL unit holds more than one message")
        unit = unit[:2] + size_bytes(size - 1) + payload[: size - 1]
        changed += 1
    units.append(unit)
if changed == 0:
    raise SystemExit("the stream holds no SEI NAL unit whose message text ends with a zero byte")
open(target, "wb").write(b"".join(b"\x00\x00\x00\x01" + unit for unit in units))
