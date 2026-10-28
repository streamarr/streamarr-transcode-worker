#!/usr/bin/env python3
"""Puts each Dolby Vision RPU of a raw HEVC stream on TemporalId 1.

    raise-rpu-temporal-id.py <source> <target>

An RPU is a NAL unit of type 62 whose header names nuh_layer_id 0 and nuh_temporal_id_plus1 1.
FFmpeg's HEVC decoder reads a type 62 NAL unit as an RPU only on layer 0 and TemporalId 0, so the
target sets nuh_temporal_id_plus1 to 2 in each RPU's header, and its frames carry no RPU.
"""
import sys

RPU_HEADER = b"\x00\x00\x01\x7c\x01"
RAISED_HEADER = b"\x00\x00\x01\x7c\x02"

source, target = sys.argv[1], sys.argv[2]
data = open(source, "rb").read()
if RPU_HEADER not in data:
    raise SystemExit("the stream holds no RPU on layer 0 and TemporalId 0")
open(target, "wb").write(data.replace(RPU_HEADER, RAISED_HEADER))
