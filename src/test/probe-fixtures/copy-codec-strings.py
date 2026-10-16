#!/usr/bin/env python3
"""Copies each recorded stream as the worker copies it, and reads the codec string of each copy.

  copy-codec-strings.py commands WORK FIXTURE...   prints the copy commands for the recording image
  copy-codec-strings.py read WORK EXPECTATIONS     merges each copy's codec string into EXPECTATIONS

The copy uses the worker's copy arguments: the tag rule's avc1 or hvc1 sample entry after the
Annex B filter, aac_adtstoasc for AAC, and the fragmented MP4 output. The reader builds the RFC 6381
string from the initialization segment's sample entry and its avcC, hvcC, av1C or esds box.
"""
import json
import os
import struct
import sys

COPIED_CODECS = {'h264', 'hevc', 'av1', 'aac', 'ac3', 'eac3', 'opus', 'flac', 'alac'}
VIDEO_TAGS = {'h264': ('h264_mp4toannexb', 'avc1'), 'hevc': ('hevc_mp4toannexb', 'hvc1')}
MOVFLAGS = 'cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont'


def copy_arguments(stream):
    codec = stream['codec_name']
    if stream['codec_type'] == 'video':
        if codec in VIDEO_TAGS:
            annex_b, tag = VIDEO_TAGS[codec]
            return ['-c:v', 'copy', '-bsf:v', annex_b, '-tag:v', tag, '-strict', 'unofficial']
        return ['-c:v', 'copy', '-strict', 'unofficial']
    arguments = ['-c:a', 'copy']
    if codec == 'aac':
        arguments += ['-bsf:a', 'aac_adtstoasc']
    return arguments + ['-b:a', '0', '-strict', 'unofficial']


def commands(work, fixtures):
    print('set -euo pipefail')
    print('mkdir -p /work/copies')
    for fixture in fixtures:
        output = json.load(open(os.path.join(work, fixture + '.json')))
        source = f'/work/{fixture}' if os.path.exists(os.path.join(work, fixture)) else f'/samples/{fixture}'
        for stream in output.get('streams', []):
            if stream.get('codec_name') not in COPIED_CODECS:
                continue
            if stream.get('disposition', {}).get('attached_pic') == 1:
                continue
            # An audio stream without packets has no sample rate, and the mp4 muxer refuses it.
            if stream['codec_type'] == 'audio' and int(stream.get('sample_rate', '0')) == 0:
                continue
            index = stream['index']
            arguments = ['timeout', '60', 'ffmpeg', '-hide_banner', '-nostdin', '-loglevel', 'error', '-y',
                         '-i', source, '-map', f'0:{index}', '-t', '1'] + copy_arguments(stream) + [
                '-f', 'mp4', '-movflags', MOVFLAGS, '-frag_duration', '1000000',
                f'/work/copies/{fixture}.{index}.mp4']
            print(' '.join(f"'{argument}'" for argument in arguments))


def boxes(data, start, end):
    while start + 8 <= end:
        size, kind = struct.unpack('>I4s', data[start:start + 8])
        header = 8
        if size == 1:
            size = struct.unpack('>Q', data[start + 8:start + 16])[0]
            header = 16
        if size < header:
            return
        yield kind.decode('latin1'), start + header, start + size
        start += size


def child(data, start, end, path):
    for kind, body, box_end in boxes(data, start, end):
        if kind == path[0]:
            return (body, box_end) if len(path) == 1 else child(data, body, box_end, path[1:])
    raise ValueError(f'no {path[0]} box')


def descriptor(data, at):
    tag = data[at]
    at += 1
    size = 0
    for _ in range(4):
        size = (size << 7) | (data[at] & 0x7F)
        at += 1
        if not data[at - 1] & 0x80:
            break
    return tag, at, at + size


def mp4a(data, body, end):
    esds, _ = child(data, body, end, ['esds'])
    tag, at, _ = descriptor(data, esds + 4)
    assert tag == 3
    flags = data[at + 2]
    at += 3
    at += 2 if flags & 0x80 else 0
    at += 1 + data[at] if flags & 0x40 else 0
    at += 2 if flags & 0x20 else 0
    tag, at, config_end = descriptor(data, at)
    assert tag == 4
    object_type = data[at]
    if object_type != 0x40 or at + 13 >= config_end:
        return f'mp4a.{object_type:02X}'
    tag, at, _ = descriptor(data, at + 13)
    assert tag == 5
    audio_object_type = data[at] >> 3
    if audio_object_type == 31:
        audio_object_type = 32 + (((data[at] & 7) << 3) | (data[at + 1] >> 5))
    return f'mp4a.40.{audio_object_type}'


def hevc(entry, record):
    space_tier_profile = record[1]
    compatibility = int(f'{struct.unpack(">I", record[2:6])[0]:032b}'[::-1], 2)
    constraints = list(record[6:12])
    while constraints and constraints[-1] == 0:
        constraints.pop()
    tier = 'H' if space_tier_profile & 0x20 else 'L'
    return (f"{entry}.{['', 'A', 'B', 'C'][space_tier_profile >> 6]}{space_tier_profile & 0x1F}"
            f".{compatibility:X}.{tier}{record[12]}" + ''.join(f'.{value:X}' for value in constraints))


def av1(record):
    profile, level = record[1] >> 5, record[1] & 0x1F
    tier = 'H' if record[2] & 0x80 else 'M'
    high, twelve = (record[2] >> 6) & 1, (record[2] >> 5) & 1
    depth = 12 if profile == 2 and high and twelve else 10 if high else 8
    return f'av01.{profile}.{level:02d}{tier}.{depth:02d}'


def codec_string(path):
    data = open(path, 'rb').read()
    stsd, stsd_end = child(data, 0, len(data), ['moov', 'trak', 'mdia', 'minf', 'stbl', 'stsd'])
    entry, body, end = next(boxes(data, stsd + 8, stsd_end))
    if entry in ('avc1', 'hvc1', 'av01'):
        record_box = {'avc1': 'avcC', 'hvc1': 'hvcC', 'av01': 'av1C'}[entry]
        record, _ = child(data, body + 78, end, [record_box])
        if entry == 'avc1':
            return f'avc1.{data[record + 1]:02X}{data[record + 2]:02X}{data[record + 3]:02X}'
        return hevc(entry, data[record:record + 13]) if entry == 'hvc1' else av1(data[record:record + 3])
    if entry == 'mp4a':
        version = struct.unpack('>H', data[body + 8:body + 10])[0]
        return mp4a(data, body + 28 + {1: 16, 2: 36}.get(version, 0), end)
    return entry


def read(work, expectations_path):
    expectations = json.load(open(expectations_path)) if os.path.exists(expectations_path) else {}
    copies = os.path.join(work, 'copies')
    for name in sorted(os.listdir(copies)):
        fixture, index, _ = name.rsplit('.', 2)
        expectations.setdefault(fixture, {})[index] = codec_string(os.path.join(copies, name))
    with open(expectations_path, 'w') as output:
        json.dump(dict(sorted(expectations.items())), output, indent=2, sort_keys=True)
        output.write('\n')


if __name__ == '__main__':
    if sys.argv[1] == 'commands':
        commands(sys.argv[2], sys.argv[3:])
    else:
        read(sys.argv[2], sys.argv[3])
