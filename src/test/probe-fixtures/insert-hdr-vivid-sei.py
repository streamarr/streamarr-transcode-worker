#!/usr/bin/env python3
"""Puts a CUVA HDR Vivid message ahead of each picture of a raw HEVC stream, repeated.

    insert-hdr-vivid-sei.py <source> <target> <repetitions>

The target puts a prefix SEI NAL unit ahead of the first slice of each picture. It carries one
user_data_registered_itu_t_t35 message (payloadType 4) with country code 0x26, provider code 0x0004
and provider oriented code 0x0005, which FFmpeg parses as HDR Vivid metadata (T/UWA 005.1): system
start code 1, the four maxrgb values of one window, and no tone mapping or color saturation
mapping. The target holds the source that many times over, so that a one-picture source holds that
many pictures.
"""
import sys

T35_CUVA = bytes([0x26, 0x00, 0x04, 0x00, 0x05])
# system_start_code 1, then minimum, average, variance and maximum maxrgb in 12 bits each
# (0x010, 0x200, 0x080, 0x800), tone_mapping_mode_flag 0, color_saturation_mapping_flag 0, and
# 6 bits of padding.
VIVID = bytes([0x01, 0x01, 0x02, 0x00, 0x08, 0x08, 0x00, 0x00])


def escaped(rbsp):
    output = bytearray()
    zeros = 0
    for byte in rbsp:
        if zeros >= 2 and byte <= 3:
            output.append(3)
            zeros = 0
        output.append(byte)
        zeros = zeros + 1 if byte == 0 else 0
    return bytes(output)


message = T35_CUVA + VIVID
sei = b"\x4e\x01" + escaped(bytes([4, len(message)]) + message + b"\x80")

source, target, repetitions = sys.argv[1], sys.argv[2], int(sys.argv[3])
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
    unit = data[start:end].rstrip(b"\x00")
    # A slice NAL unit (type 0 to 31) whose first_slice_segment_in_pic_flag is set starts a picture.
    if (unit[0] >> 1) & 0x3F < 32 and unit[2] & 0x80:
        units.append(sei)
    units.append(unit)
if sei not in units:
    raise SystemExit("the stream holds no picture")
open(target, "wb").write(b"".join(b"\x00\x00\x00\x01" + unit for unit in units) * repetitions)
