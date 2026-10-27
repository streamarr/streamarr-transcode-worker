#!/usr/bin/env python3
"""Inserts HDR metadata OBUs into the temporal units of an AV1 stream in IVF.

    insert-av1-metadata.py <variant> <source.ivf> <target.ivf> [rpus.hevc]

Each variant puts metadata OBUs (obu_type 5) into the first temporal unit, ahead of its first frame
or frame header OBU, or after its last OBU:

    hdr10-plus-rejected  an HDR10+ ITU-T T.35 OBU whose body FFmpeg cannot parse, ahead of the
                         frame, so that FFmpeg's AV1 decoders drop the frame and no frame shows it
    static-after-frame   HDR_MDCV and HDR_CLL OBUs after the frame, so that they reach the second
                         frame and the first frame shows neither
    mdcv-truncated       an HDR_MDCV OBU that ends after 10 of its 24 bytes, ahead of the frame
    mdcv-layered         an HDR_MDCV OBU with an extension byte for temporal and spatial layer 0,
                         ahead of the frame
    rpu                  a Dolby Vision RPU in its EMDF container, ahead of the frame of every
                         temporal unit
    rpu-bad-emdf         the same RPU in a container whose header FFmpeg rejects, so that no frame
                         shows it

The RPU variants take the first Dolby Vision RPU (NAL unit type 62) of a raw HEVC stream.
"""
import struct
import sys

T35_HDR10_PLUS = bytes([0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04])
T35_DOLBY_VISION = bytes([0xB5, 0x00, 0x3B, 0x00, 0x00, 0x08, 0x00])
EMDF_HEADER = 0x01BE6841
FRAME_OBU_TYPES = (3, 6)


def leb128(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_leb128(data, position):
    value = 0
    for index in range(8):
        byte = data[position]
        position += 1
        value |= (byte & 0x7F) << (7 * index)
        if not byte & 0x80:
            break
    return value, position


def obus(unit):
    """Yields each OBU's type and bytes."""
    position = 0
    while position < len(unit):
        start = position
        header = unit[position]
        position += 1
        if header & 0x04:
            position += 1
        if header & 0x02:
            size, position = read_leb128(unit, position)
        else:
            size = len(unit) - position
        position += size
        yield (header >> 3) & 0x0F, unit[start:position]


def bits(fields):
    text = "".join(format(value, "0%db" % count) for count, value in fields)
    text += "0" * (-len(text) % 8)
    return bytes(int(text[index:index + 8], 2) for index in range(0, len(text), 8))


def metadata_obu(metadata_type, values, layered=False):
    payload = leb128(metadata_type) + values + b"\x80"
    if layered:
        return bytes([0x2E, 0x00]) + leb128(len(payload)) + payload
    return bytes([0x2A]) + leb128(len(payload)) + payload


def emdf(rpu, header=EMDF_HEADER):
    if len(rpu) > 255:
        raise SystemExit("the RPU takes more than 255 bytes")
    fields = [(27, header), (8, len(rpu)), (1, 0)] + [(8, byte) for byte in rpu] + [(17, 0x400)]
    return bits(fields)


def first_rpu(path):
    """The first RPU's bytes after its NAL unit header and rpu_nal_prefix, without emulation
    prevention and trailing zero bytes."""
    data = open(path, "rb").read()
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
        unit = data[start:end].rstrip(b"\x00")
        if (unit[0] >> 1) & 0x3F == 62:
            unit = unit.replace(b"\x00\x00\x03", b"\x00\x00")
            if unit[2] != 25:
                raise SystemExit("the first RPU lacks its rpu_nal_prefix")
            return unit[3:]
    raise SystemExit("the stream holds no RPU")


def read_ivf(path):
    data = open(path, "rb").read()
    header, position, units = data[:32], 32, []
    while position < len(data):
        size, pts = struct.unpack("<IQ", data[position:position + 12])
        position += 12
        units.append((pts, data[position:position + size]))
        position += size
    return header, units


def write_ivf(path, header, units):
    out = bytearray(header)
    for pts, unit in units:
        out += struct.pack("<IQ", len(unit), pts) + unit
    open(path, "wb").write(out)


def insert(unit, added, after):
    if after:
        return unit + added
    out = bytearray()
    inserted = False
    for obu_type, raw in obus(unit):
        if not inserted and obu_type in FRAME_OBU_TYPES:
            out += added
            inserted = True
        out += raw
    if not inserted:
        raise SystemExit("a temporal unit holds no frame")
    return bytes(out)


def main():
    variant, source, target = sys.argv[1], sys.argv[2], sys.argv[3]
    mastering = bits([(16, 44564), (16, 20972), (16, 17367), (16, 45220), (16, 9830), (16, 3932),
                      (16, 20493), (16, 21561), (32, 1000 << 8), (32, 82)])
    light = bits([(16, 1000), (16, 400)])
    if variant == "hdr10-plus-rejected":
        added, units_with, after = metadata_obu(4, T35_HDR10_PLUS + b"\x01\x00"), {0}, False
    elif variant == "static-after-frame":
        added, units_with, after = metadata_obu(2, mastering) + metadata_obu(1, light), {0}, True
    elif variant == "mdcv-truncated":
        added, units_with, after = metadata_obu(2, mastering[:10]), {0}, False
    elif variant == "mdcv-layered":
        added, units_with, after = metadata_obu(2, mastering, layered=True), {0}, False
    elif variant in ("rpu", "rpu-bad-emdf"):
        header = EMDF_HEADER if variant == "rpu" else EMDF_HEADER - 1
        added = metadata_obu(4, T35_DOLBY_VISION + emdf(first_rpu(sys.argv[4]), header))
        units_with, after = None, False
    else:
        raise SystemExit("unknown variant " + variant)
    ivf_header, units = read_ivf(source)
    units = [(pts, insert(unit, added, after) if units_with is None or index in units_with
              else unit) for index, (pts, unit) in enumerate(units)]
    write_ivf(target, ivf_header, units)


main()
