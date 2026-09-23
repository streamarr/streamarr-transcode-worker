#!/usr/bin/env python3
"""Independent reader for FFmpeg's fragmented MP4 output (no Java, no FFmpeg).

Reads top-level ISOBMFF boxes: ftyp + moov form the initialization segment; each moof with the
mdat that follows it forms one fragment. For every fragment it reports, per track, the first
sample's presentation time (tfdt baseMediaDecodeTime + that sample's composition offset from
trun, signed when trun version is 1, no edit list), whether that sample is a sync sample
(trun first_sample_flags, else trun per-sample flags of sample 0, else tfhd default_sample_flags,
else trex default; sync iff sample_is_non_sync_sample 0x00010000 is clear), the sample count and
the sum of sample durations (reported only to show it is not a usable fragment end).

Usage: fmp4dump.py FILE [FILE ...]   (several files are read as one concatenated stream, e.g.
init.mp4 segment0.m4s segment1.m4s for an HLS output)
"""

import json
import struct
import sys

NON_SYNC = 0x00010000


def boxes(data, start, end):
    pos = start
    while pos < end:
        if end - pos < 8:
            raise ValueError(f"truncated box header at {pos}")
        size, kind = struct.unpack(">I4s", data[pos : pos + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[pos + 8 : pos + 16])[0]
            header = 16
        if size == 0:
            raise ValueError(f"size-0 (to end of file) box {kind!r} at {pos}")
        if pos + size > end:
            raise ValueError(f"box {kind!r} at {pos} overruns its parent")
        yield kind.decode("latin-1"), pos, pos + header, pos + size
        pos += size


def child(data, start, end, kind):
    for k, _, body, stop in boxes(data, start, end):
        if k == kind:
            return body, stop
    return None


def full_box(data, body):
    version = data[body]
    flags = int.from_bytes(data[body + 1 : body + 4], "big")
    return version, flags, body + 4


def parse_moov(data, body, stop):
    tracks = {}
    for kind, _, tbody, tstop in boxes(data, body, stop):
        if kind == "trak":
            tkhd = child(data, tbody, tstop, "tkhd")
            version, _, p = full_box(data, tkhd[0])
            track_id = struct.unpack(">I", data[p + (16 if version == 1 else 8) : p + (20 if version == 1 else 12)])[0]
            mdia = child(data, tbody, tstop, "mdia")
            mdhd = child(data, mdia[0], mdia[1], "mdhd")
            version, _, p = full_box(data, mdhd[0])
            timescale = struct.unpack(">I", data[p + (16 if version == 1 else 8) : p + (20 if version == 1 else 12)])[0]
            hdlr = child(data, mdia[0], mdia[1], "hdlr")
            handler = data[hdlr[0] + 8 : hdlr[0] + 12].decode("latin-1")
            edts = child(data, tbody, tstop, "edts")
            edits = []
            if edts:
                elst = child(data, edts[0], edts[1], "elst")
                version, _, p = full_box(data, elst[0])
                count = struct.unpack(">I", data[p : p + 4])[0]
                p += 4
                for _ in range(count):
                    if version == 1:
                        duration, media_time = struct.unpack(">Qq", data[p : p + 16])
                        p += 16
                    else:
                        duration, media_time = struct.unpack(">Ii", data[p : p + 8])
                        p += 8
                    p += 4
                    edits.append([duration, media_time])
            tracks[track_id] = {"trackId": track_id, "handler": handler, "timescale": timescale, "edits": edits}
    mvex = child(data, body, stop, "mvex")
    if mvex:
        for kind, _, tbody, _ in boxes(data, mvex[0], mvex[1]):
            if kind == "trex":
                _, _, p = full_box(data, tbody)
                track_id, _, duration, size, flags = struct.unpack(">IIIII", data[p : p + 20])
                if track_id in tracks:
                    tracks[track_id].update(trexDuration=duration, trexSize=size, trexFlags=flags)
    return tracks


def parse_traf(data, body, stop, tracks):
    tfhd = child(data, body, stop, "tfhd")
    _, flags, p = full_box(data, tfhd[0])
    track_id = struct.unpack(">I", data[p : p + 4])[0]
    p += 4
    track = tracks[track_id]
    default_duration = track.get("trexDuration", 0)
    default_flags = track.get("trexFlags", 0)
    default_size = track.get("trexSize", 0)
    if flags & 0x1:
        p += 8
    if flags & 0x2:
        p += 4
    if flags & 0x8:
        default_duration = struct.unpack(">I", data[p : p + 4])[0]
        p += 4
    if flags & 0x10:
        default_size = struct.unpack(">I", data[p : p + 4])[0]
        p += 4
    if flags & 0x20:
        default_flags = struct.unpack(">I", data[p : p + 4])[0]
    tfdt = child(data, body, stop, "tfdt")
    version, _, p = full_box(data, tfdt[0])
    base = struct.unpack(">Q" if version == 1 else ">I", data[p : p + (8 if version == 1 else 4)])[0]
    samples = []
    for kind, _, tbody, _ in boxes(data, body, stop):
        if kind != "trun":
            continue
        version, tflags, p = full_box(data, tbody)
        count = struct.unpack(">I", data[p : p + 4])[0]
        p += 4
        if tflags & 0x1:
            p += 4
        first_flags = None
        if tflags & 0x4:
            first_flags = struct.unpack(">I", data[p : p + 4])[0]
            p += 4
        for i in range(count):
            duration = default_duration
            sample_flags = default_flags
            cto = 0
            if tflags & 0x100:
                duration = struct.unpack(">I", data[p : p + 4])[0]
                p += 4
            size = default_size
            if tflags & 0x200:
                size = struct.unpack(">I", data[p : p + 4])[0]
                p += 4
            if tflags & 0x400:
                sample_flags = struct.unpack(">I", data[p : p + 4])[0]
                p += 4
            if tflags & 0x800:
                cto = struct.unpack(">i" if version == 1 else ">I", data[p : p + 4])[0]
                p += 4
            if i == 0 and first_flags is not None:
                sample_flags = first_flags
            samples.append((duration, sample_flags, cto, size))
    first = samples[0] if samples else None
    return {
        "trackId": track_id,
        "handler": track["handler"],
        "timescale": track["timescale"],
        "baseMediaDecodeTime": base,
        "sampleCount": len(samples),
        "firstPresentationTime": base + first[2] if first else None,
        "firstSync": (first[1] & NON_SYNC) == 0 if first else None,
        "durationSum": sum(s[0] for s in samples),
        "syncSamples": sum(1 for s in samples if (s[1] & NON_SYNC) == 0),
        "samples": samples,
    }


def read(paths):
    data = b"".join(open(p, "rb").read() for p in paths)
    init_end = None
    tracks = None
    fragments = []
    pending_moof = None
    for kind, start, body, stop in boxes(data, 0, len(data)):
        if kind == "moov":
            tracks = parse_moov(data, body, stop)
            init_end = stop
        elif kind == "moof":
            if tracks is None:
                raise ValueError("moof before moov")
            if pending_moof is not None:
                raise ValueError(f"moof at {start} follows a moof with no mdat")
            trafs = [parse_traf(data, b, s, tracks) for k, _, b, s in boxes(data, body, stop) if k == "traf"]
            pending_moof = {"offset": start, "trafs": trafs}
        elif kind == "mdat":
            if pending_moof is None:
                raise ValueError(f"mdat at {start} without moof")
            pending_moof["byteLength"] = stop - pending_moof["offset"]
            fragments.append(pending_moof)
            pending_moof = None
    if pending_moof is not None:
        raise ValueError("stream ends after a moof with no mdat")
    return {"initByteLength": init_end, "initBytes": data[:init_end], "tracks": tracks, "fragments": fragments}


def video_samples(stream):
    """Every video sample in decode order: (presentation time, sync, size, fragment index)."""
    result = []
    for index, fragment in enumerate(stream["fragments"]):
        for traf in fragment["trafs"]:
            if traf["handler"] != "vide":
                continue
            decode = traf["baseMediaDecodeTime"]
            for duration, flags, cto, size in traf["samples"]:
                result.append((decode + cto, (flags & NON_SYNC) == 0, size, index))
                decode += duration
    return result


def signed(value):
    return value - (1 << 64) if value >= 1 << 63 else value


def video_start(fragment):
    for traf in fragment["trafs"]:
        if traf["handler"] == "vide" and traf["sampleCount"] > 0:
            return traf
    return None


if __name__ == "__main__":
    result = read(sys.argv[1:])
    del result["initBytes"]
    for fragment in result["fragments"]:
        for traf in fragment["trafs"]:
            del traf["samples"]
    print(json.dumps(result, indent=1))
