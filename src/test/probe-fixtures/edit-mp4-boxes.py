#!/usr/bin/env python3
"""Edits the boxes of a small MP4 that FFmpeg wrote, for probe fixtures that FFmpeg cannot write.

    edit-mp4-boxes.py clap <file> <width> <height>   adds a clap box to the first video sample entry
    edit-mp4-boxes.py udta-first <file>              moves moov's udta box before its trak boxes
    edit-mp4-boxes.py er-aac-lc <file>               makes the first AAC config ER AAC LC

clap names a centered clean aperture of <width> by <height> pixels (ISO/IEC 14496-12, 12.1.4).
FFmpeg's MP4 demuxer creates a stream for each trak box and for the cover art in udta in the order
of the boxes, so udta-first makes the cover art the first video stream. er-aac-lc
rewrites the AudioSpecificConfig in the first esds box as audio object type 17, ER AAC LC, with
the same sampling frequency index and channel configuration (ISO/IEC 14496-3, 1.6.2.1). FFmpeg
names no profile for that object type. Both edits keep moov's
size or move only bytes inside moov, which FFmpeg writes after mdat, so no chunk offset changes.
"""
import struct
import sys

def boxes(data, start, end):
    position = start
    while position < end:
        size, kind = struct.unpack(">I4s", data[position : position + 8])
        yield position, size, kind
        position += size


def find(data, start, end, kind):
    return next((position, size) for position, size, k in boxes(data, start, end) if k == kind)


def add_clap(data, width, height):
    moov, moov_size = find(data, 0, len(data), b"moov")
    path = [(moov, moov_size)]
    for kind in (b"trak", b"mdia", b"minf", b"stbl", b"stsd"):
        parent, parent_size = path[-1]
        path.append(find(data, parent + 8, parent + parent_size, kind))
    stsd, stsd_size = path[-1]
    # stsd is a full box with an entry count, so its first sample entry starts 16 bytes in.
    entry = stsd + 16
    entry_size = struct.unpack(">I", data[entry : entry + 4])[0]
    clap = struct.pack(">I4s8I", 40, b"clap", width, 1, height, 1, 0, 1, 0, 1)
    insert_at = entry + entry_size
    data = data[:insert_at] + clap + data[insert_at:]
    for position, _ in path + [(entry, entry_size)]:
        size = struct.unpack(">I", data[position : position + 4])[0]
        data = data[:position] + struct.pack(">I", size + len(clap)) + data[position + 4 :]
    return data


def udta_first(data):
    moov, moov_size = find(data, 0, len(data), b"moov")
    children = [(p, s, k) for p, s, k in boxes(data, moov + 8, moov + moov_size)]
    ordered = [c for c in children if c[2] == b"mvhd"]
    ordered += [c for c in children if c[2] == b"udta"]
    ordered += [c for c in children if c[2] not in (b"mvhd", b"udta")]
    body = b"".join(data[p : p + s] for p, s, _ in ordered)
    return data[: moov + 8] + body + data[moov + moov_size :]


def er_aac_lc(data):
    esds = data.find(b"esds")
    # DecoderSpecificInfo has tag 5. FFmpeg writes its length in four bytes: 80 80 80 and the size.
    info = data.find(b"\x05\x80\x80\x80", esds)
    config = info + 5
    bits = int.from_bytes(data[config : config + 2], "big")
    frequency_index = (bits >> 7) & 0xF
    channels = (bits >> 3) & 0xF
    # 5 bits of object type, then the frequency index, the channels, three GASpecificConfig flags
    # set to 0, and an epConfig of 0: 18 bits, padded to 3 bytes.
    er_config = ((17 << 13) | (frequency_index << 9) | (channels << 5)) << 6
    return data[:config] + er_config.to_bytes(3, "big") + data[config + 3 :]


def main():
    command, path = sys.argv[1], sys.argv[2]
    with open(path, "rb") as source:
        data = source.read()
    if command == "clap":
        data = add_clap(data, int(sys.argv[3]), int(sys.argv[4]))
    elif command == "udta-first":
        data = udta_first(data)
    elif command == "er-aac-lc":
        data = er_aac_lc(data)
    else:
        raise SystemExit("unknown command " + command)
    with open(path, "wb") as target:
        target.write(data)


if __name__ == "__main__":
    main()
