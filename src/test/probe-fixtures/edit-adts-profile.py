#!/usr/bin/env python3
"""Rewrites the profile of every frame header in an ADTS file.

    edit-adts-profile.py <source> <target> <profile>

FFmpeg's AAC encoders write only AAC LC and HE-AAC. The ADTS profile field holds the audio object
type minus 1 (ISO/IEC 14496-3, 1.A.2.2.1), so profile 0 names AAC Main and profile 3 names AAC LTP.
An AAC LC frame decodes as either: it sets predictor_data_present to 0, which those object types
read as no prediction and no long-term prediction.
"""
import sys

source, target, profile = sys.argv[1], sys.argv[2], int(sys.argv[3])
data = bytearray(open(source, "rb").read())
position = 0
while position + 7 <= len(data):
    if data[position] != 0xFF or data[position + 1] & 0xF0 != 0xF0:
        raise SystemExit(f"no ADTS sync word at byte {position}")
    data[position + 2] = (data[position + 2] & 0x3F) | (profile << 6)
    length = ((data[position + 3] & 0x03) << 11) | (data[position + 4] << 3) | (data[position + 5] >> 5)
    position += length
open(target, "wb").write(data)
