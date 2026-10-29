#!/usr/bin/env python3
"""Puts a message that FFmpeg rejects ahead of each HDR message of a raw H.264 or HEVC stream.

    reject-hdr-sei.py <h264|hevc> <source> <target>

The target puts a 1-byte user_data_unregistered message (payloadType 5) at the start of each SEI
NAL unit whose first message is an HDR10+ (payloadType 4), mastering display color volume (137) or
content light level (144) message. FFmpeg rejects a user_data_unregistered message shorter than 16
bytes and stops parsing its SEI NAL unit there, so the target's frames carry none of those messages
and only its packets do. The inserted bytes 05 01 00 need no emulation prevention byte, because the
message type that follows them is not 0 to 3.
"""
import sys

codec, source, target = sys.argv[1], sys.argv[2], sys.argv[3]
header_length, is_sei = {
    "h264": (1, lambda unit: unit[0] & 0x1F == 6),
    "hevc": (2, lambda unit: (unit[0] >> 1) & 0x3F in (39, 40)),
}[codec]
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
    if is_sei(unit) and len(unit) > header_length and unit[header_length] in (4, 137, 144):
        unit = unit[:header_length] + b"\x05\x01\x00" + unit[header_length:]
        changed += 1
    units.append(unit)
if changed == 0:
    raise SystemExit("the stream holds no SEI NAL unit that starts with an HDR message")
open(target, "wb").write(b"".join(b"\x00\x00\x00\x01" + unit for unit in units))
