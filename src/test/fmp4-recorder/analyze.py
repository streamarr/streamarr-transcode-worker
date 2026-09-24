#!/usr/bin/env python3
"""Derives expected.json from the recorded pipe streams and the HLS muxer oracle runs.

Independent of the worker's Java code:
  * fmp4dump.py reads the boxes of every recording;
  * group() applies ADR 0037's grouping rules to the pipe stream;
  * each HLS oracle run is read the same way, and each HLS segment's first video sample is mapped
    to the pipe recording by its ordinal in decode order (frame identity, checked by sample count
    and, where the runs share their video arguments, by every sample's byte size). Converting the
    HLS files' own timestamps is not reliable: with frag_discont the mp4 muxer rebases the first
    fragment on pts 0 and snaps every later fragment's dts to the running duration sum, so HLS
    segment timestamps drift from the source's on the variable-frame-rate copy;
  * hlsenc_cuts() models hlsenc.c's own cut rule (FFmpeg 8.1, lines 2440-2489) with its actual
    reference point, to show that every disagreement between the grid and the HLS muxer comes
    from where hlsenc measures from, not from the grouping.

  analyze.py --work WORK --out FIXTURES_DIR --image WORKER_IMAGE
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
from fractions import Fraction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fmp4dump  # noqa: E402

PERIOD = 6
FRAGMENTATION_TARGET_MICROS = 1_000_000


def oracle(run, flags, audio=True, reference=None, restrict=False, expect=True):
    """flags: "hls-recipe" (source timestamps, no -start_at_zero) or "pipe-recipe" (-start_at_zero).
    reference: the pipe recording that decodes the same frames as the HLS run (default: the fixture).
    restrict: compare only segment numbers >= the fixture's startSequenceNumber."""
    return dict(run=run, flags=flags, audio=audio, reference=reference, restrict=restrict, expect=expect)


FIXTURES = [
    dict(name="01-encode-cfr", source="cfr.mp4", mode="encode", encoder="libx264", seek=0, start=0,
         proves="Constant 23.976 fps libx264 encode of 66 s: every forced keyframe opens a segment; the "
         "frame-count-GOP keyframe one frame before most boundaries (frame 143, 287, ...) is a 1-frame "
         "sync-first fragment that joins the earlier segment.",
         oracles=[oracle("01-encode-cfr.hls-recipe", "hls-recipe"),
                  oracle("01-encode-cfr.video-only", "pipe-recipe", audio=False),
                  oracle("01-encode-cfr.pipe-keyframes-with-audio", "pipe-recipe")]),
    dict(name="01-encode-cfr-seek30", source="cfr.mp4", mode="encode", encoder="libx264", seek=30, start=5,
         proves="An encoded seek (-ss 30) under -r without -fps_mode starts at the seek point (30.030 s, "
         "segment 5), pads nothing from zero, and has no preroll; its segment starts equal the start-0 recording's.",
         oracles=[oracle("01-encode-cfr-seek30.hls-recipe", "hls-recipe", expect=False),
                  oracle("01-encode-cfr-seek30.video-only", "pipe-recipe", audio=False, expect=False),
                  oracle("01-encode-cfr.hls-recipe", "hls-recipe", reference="01-encode-cfr", restrict=True)]),
    dict(name="02-copy-irregular-keyframes", source="irregular.mp4", mode="copy", encoder=None, seek=0, start=0,
         proves="Stream copy of a 24 fps source with keyframes only at 0, 6.5, 12, 18, 24.5, 30, 36.5, 42, "
         "48, 54.5 and 60 s over 66 s (12, 18, 30, 42, 48 and 60 s sit exactly on a boundary and open that "
         "boundary's segment): 11 segments, one keyframe each.",
         oracles=[oracle("02-copy-irregular-keyframes.hls-recipe", "hls-recipe"),
                  oracle("02-copy-irregular-keyframes.video-only", "pipe-recipe", audio=False)]),
    dict(name="03-encode-vfr", source="vfr.mp4", mode="encode", encoder="libx264", seek=0, start=0,
         proves="Variable-frame-rate source (avg 16.2 fps on a 23.976 grid) encoded with libx264 under "
         "-r 23.976 and no -fps_mode: constant-rate output starting at the first source frame (41.7 ms), "
         "a keyframe in every interval.",
         oracles=[oracle("03-encode-vfr.hls-recipe", "hls-recipe", expect=False),
                  oracle("03-encode-vfr.video-only", "pipe-recipe", audio=False, expect=False)]),
    dict(name="04-copy-vfr-bframes", source="vfr.mp4", mode="copy", encoder=None, seek=0, start=0,
         proves="Variable-frame-rate stream copy with B-frames and one or two keyframes per interval: "
         "segments open only at a sync-first fragment inside a new interval, whatever the frame spacing.",
         oracles=[oracle("04-copy-vfr-bframes.hls-recipe", "hls-recipe", expect=False),
                  oracle("04-copy-vfr-bframes.video-only", "pipe-recipe", audio=False, expect=False)]),
    dict(name="05-encode-late-start", source="late.ts", mode="encode", encoder="libx264", seek=0, start=0,
         proves="MPEG-TS source whose timestamps begin at 12 s: -start_at_zero puts media time zero at the "
         "container start (the AAC priming frame, 21.3 ms before the first video frame), so the first "
         "fragment is segment 0, not segment 2.",
         oracles=[oracle("05-encode-late-start.hls-recipe", "hls-recipe", expect=False),
                  oracle("05-encode-late-start.video-only", "pipe-recipe", audio=False, expect=False)]),
    dict(name="05-copy-late-start", source="late.ts", mode="copy", encoder=None, seek=0, start=0,
         proves="Stream copy of the same 12 s-origin MPEG-TS source with -bsf:a aac_adtstoasc: the first "
         "fragment is segment 0 and every keyframe's media time is its source timestamp minus the container start.",
         oracles=[oracle("05-copy-late-start.hls-recipe-adtstoasc", "hls-recipe"),
                  oracle("05-copy-late-start.video-only", "pipe-recipe", audio=False)]),
    dict(name="06-encode-audio-tail", source="tail.mp4", mode="encode", encoder="libx264", seek=0, start=0,
         proves="Audio outlasts video by 3.5 s: the recording ends in audio-only fragments (no video traf), which "
         "join the last open segment; none precedes the first segment.",
         oracles=[oracle("06-encode-audio-tail.hls-recipe", "hls-recipe"),
                  oracle("06-encode-audio-tail.video-only", "pipe-recipe", audio=False)]),
    dict(name="07-copy-start0", source="cfr.mp4", mode="copy", encoder=None, seek=0, start=0,
         proves="Stream copy of a 2.002 s-GOP 23.976 fps source from the start: three keyframes per segment.",
         oracles=[oracle("07-copy-start0.hls-recipe", "hls-recipe"),
                  oracle("07-copy-start0.video-only", "pipe-recipe", audio=False)]),
    dict(name="07-copy-seek30", source="cfr.mp4", mode="copy", encoder=None, seek=30, start=5,
         proves="Stream-copy replacement attempt at -ss 30 (start sequence number 5): the seek lands on the keyframe at "
         "28.028 s (segment 4), which is preroll and is discarded with the non-sync fragment after it; "
         "segments 5 to 10 carry the start-0 recording's video samples at the same ticks (audio packets regroup by one AAC frame).",
         oracles=[oracle("07-copy-start0.hls-recipe", "hls-recipe", reference="07-copy-start0", restrict=True),
                  oracle("07-copy-seek30.hls-recipe", "hls-recipe", expect=False)]),
    dict(name="09-svtav1-vfr", source="vfr.mp4", mode="encode", encoder="libsvtav1", seek=0, start=0,
         proves="Irregular variable-frame-rate source through SVT-AV1 with -r 23.976, a frame-count GOP of "
         "floor(6 x 23.976) = 143 and time-based forced keyframes: a keyframe in every interval, on the same "
         "frames libx264 chose in 03.",
         oracles=[oracle("09-svtav1-vfr.video-only", "pipe-recipe", audio=False, expect=False),
                  oracle("09-svtav1-vfr.hls-recipe", "hls-recipe", expect=False),
                  oracle("09-svtav1-vfr.pipe-keyframes-with-audio", "pipe-recipe", expect=True)]),
    dict(name="09-svtav1-vfr-seek30", source="vfr.mp4", mode="encode", encoder="libsvtav1", seek=30, start=5,
         proves="The same SVT-AV1 recipe after -ss 30: starts at the first source frame after the seek point "
         "(30.072 s), pads nothing from zero, keyframe in every interval.",
         oracles=[oracle("09-svtav1-vfr-seek30.video-only", "pipe-recipe", audio=False, expect=False),
                  oracle("09-svtav1-vfr-seek30.hls-recipe", "hls-recipe", expect=False)]),
    dict(name="10-copy-gop-exceeds-period", source="gop10.mp4", mode="copy", encoder=None, seek=0, start=0,
         proves="Stream copy with a keyframe every 10.01 s: interval 2 holds no keyframe, so grouping "
         "fails with a skipped segment number at the sync-first fragment at 20.02 s (segment 3).",
         oracles=[oracle("10-copy-gop-exceeds-period.hls-recipe", "hls-recipe", expect=False)]),
]

INITIALIZATION_SEGMENT_IDENTITY_PAIRS = [
    ("encode (libx264), start 0 vs -ss 30", "01-encode-cfr", "01-encode-cfr-seek30"),
    ("stream copy, start 0 vs -ss 30", "07-copy-start0", "07-copy-seek30"),
    ("encode (libsvtav1), start 0 vs -ss 30", "09-svtav1-vfr", "09-svtav1-vfr-seek30"),
]
INITIALIZATION_SEGMENT_DIFFERENCE_PAIRS = [
    ("encode vs stream copy of the same source", "01-encode-cfr", "07-copy-start0"),
]


class GroupingFailure(Exception):
    """reason is the name of the FragmentedMp4Exception.Reason the worker's grouper fails with."""

    def __init__(self, reason, **detail):
        super().__init__(reason)
        self.reason = reason
        self.detail = detail


class Grouping:
    """ADR 0037 grouping of one stream's fragments, fed in arrival order by fragment index."""

    def __init__(self, period, start):
        self.period, self.start = period, start
        self.delivered, self.preroll, self.waiting = [], [], []
        self.open = None
        self.last_sync_time = None

    def accept(self, index, video):
        if video is None or not video["firstSync"]:
            self.join(index)
            return
        time = video["firstPresentationTime"]
        if self.last_sync_time is not None and time < self.last_sync_time:
            raise GroupingFailure("PRESENTATION_TIME_REGRESSED", fragmentIndex=index, presentationTime=time)
        self.last_sync_time = time
        number = time // (self.period * video["timescale"])
        if self.open is not None and number == self.open["number"]:
            self.join(index)
            return
        expected = self.next_deliverable()
        if number > expected:
            raise GroupingFailure("SKIPPED_SEGMENT_NUMBER", fragmentIndex=index, expectedNumber=expected,
                                  actualNumber=number, presentationTime=time)
        self.close()
        self.open = {"number": number, "firstVideoPresentationTime": time, "fragments": self.waiting + [index]}
        self.waiting = []

    def join(self, index):
        if self.open is None:
            self.waiting.append(index)
            return
        self.open["fragments"].append(index)

    def next_deliverable(self):
        """The start number until a segment opens, then the start number or one past the open segment."""
        if self.open is None:
            return self.start
        return max(self.start, self.open["number"] + 1)

    def close(self):
        if self.open is None:
            return
        closed, self.open = self.open, None
        if closed["number"] < self.start:
            self.preroll.append(closed)
            return
        self.delivered.append(closed)


def group(stream, period, start):
    """ADR 0037 grouping. Returns (delivered segments, discarded preroll, failure or None)."""
    grouping = Grouping(period, start)
    try:
        for index, fragment in enumerate(stream["fragments"]):
            grouping.accept(index, fmp4dump.video_start(fragment))
        grouping.close()
    except GroupingFailure as failure:
        return grouping.delivered, grouping.preroll, failure
    return grouping.delivered, grouping.preroll, None


def describe(segment, stream):
    fragments = [stream["fragments"][i] for i in segment["fragments"]]
    return {
        "number": segment["number"],
        "firstVideoPresentationTime": segment["firstVideoPresentationTime"],
        "firstFragmentIndex": segment["fragments"][0],
        "fragmentCount": len(fragments),
        "syncFirstFragmentCount": sum(1 for f in fragments if (fmp4dump.video_start(f) or {}).get("firstSync")),
        "byteLength": sum(f["byteLength"] for f in fragments),
    }


def load_source(work, source):
    probe = json.load(open(os.path.join(work, "src", source + ".probe.json")))
    video = next(s for s in probe["streams"] if s["codec_type"] == "video")
    num, den = (int(x) for x in video["time_base"].split("/"))
    keyframes = [Fraction(int(line.split(",")[0]) * num, den)
                 for line in open(os.path.join(work, "src", source + ".keyframes.csv")) if line.strip()]
    # -start_at_zero subtracts the container start in microseconds, rescaled (rounded) to each stream's
    # time base (fftools ts_offset); for the video stream that is exactly this value.
    micros = Fraction(probe["format"]["start_time"]) * 1_000_000
    start = Fraction(round(micros * den / (num * 1_000_000)) * num, den)
    return {"start": start, "startMicros": int(micros), "video": video, "keyframes": keyframes}


def read_hls(work, run):
    folder = os.path.join(work, "hls", run)
    playlist = open(os.path.join(folder, "stream.m3u8")).read()
    sequence = int(re.search(r"#EXT-X-MEDIA-SEQUENCE:(\d+)", playlist).group(1))
    uris = [line for line in playlist.splitlines() if line and not line.startswith("#")]
    init = os.path.join(folder, "init.mp4")
    tracks = fmp4dump.read([init])["tracks"]
    video_track = next(t for t in tracks.values() if t["handler"] == "vide")
    audio_track = next((t for t in tracks.values() if t["handler"] == "soun"), None)
    edit = next((m for _, m in video_track["edits"] if m >= 0), 0)
    samples, segments, audio_first = [], [], None
    for offset, uri in enumerate(uris):
        stream = fmp4dump.read([init, os.path.join(folder, uri)])
        video = next(fmp4dump.video_start(f) for f in stream["fragments"] if fmp4dump.video_start(f))
        if audio_first is None:
            audio_first = next((fmp4dump.signed(t["baseMediaDecodeTime"]) for f in stream["fragments"]
                                for t in f["trafs"] if t["handler"] == "soun"), None)
        segments.append({"number": sequence + offset, "ordinal": len(samples),
                         "ownPresentation": video["firstPresentationTime"] - edit})
        samples.extend(fmp4dump.video_samples(stream))
    return {
        "exitStatus": int(open(os.path.join(folder, "exit-status")).read()),
        "segments": segments,
        "samples": samples,
        "videoTimescale": video_track["timescale"],
        "edit": edit,
        "audioTimescale": audio_track["timescale"] if audio_track else None,
        "audioFirstRaw": audio_first,
    }


def hlsenc_cuts(keyframe_ordinals, pts_raw, reference, period):
    """hlsenc.c: the first packet opens segment 0 (number becomes 1); a later keyframe cuts when
    (pts - start_pts) >= hls_time * number in the video time base, and number counts segments.
    pts_raw and reference are in seconds at the video time base."""
    cuts, number, current = [0], 1, pts_raw[0]
    for ordinal in keyframe_ordinals:
        if ordinal == 0 or pts_raw[ordinal] - current <= 0:
            continue
        if pts_raw[ordinal] - reference >= period * number:
            cuts.append(ordinal)
            number += 1
            current = pts_raw[ordinal]
    return cuts


def evaluate_oracle(work, fixture, spec, pipe, grouped, source):
    hls = read_hls(work, spec["run"])
    reference_name = spec["reference"] or fixture["name"]
    reference = pipe[reference_name]
    ref_samples = fmp4dump.video_samples(reference["stream"])
    timescale = reference["videoTimescale"]
    identity = {
        "hlsVideoSamples": len(hls["samples"]),
        "pipeVideoSamples": len(ref_samples),
        "sampleSizesIdentical": [s[2] for s in hls["samples"]] == [s[2] for s in ref_samples],
    }
    if len(hls["samples"]) != len(ref_samples) or hls["videoTimescale"] != timescale:
        raise ValueError(f"{spec['run']}: not the same frames as {reference_name}: {identity}")

    offset = source["start"] if spec["flags"] == "hls-recipe" else Fraction(0)
    offset_ticks = offset * timescale
    converted, direct_agrees = [], True
    for s in hls["segments"]:
        pts = ref_samples[s["ordinal"]][0]
        direct = Fraction(s["ownPresentation"]) - offset_ticks
        direct_agrees = direct_agrees and direct == pts
        if spec["restrict"] and s["number"] < fixture["start"]:
            continue
        converted.append({"number": s["number"], "firstVideoPresentationTime": pts})

    ours = {s["number"]: s["firstVideoPresentationTime"] for s in grouped}
    theirs = {s["number"]: s["firstVideoPresentationTime"] for s in converted}
    mismatches = [{"number": n, "grouping": ours.get(n), "hls": theirs.get(n)}
                  for n in sorted(set(ours) | set(theirs)) if ours.get(n) != theirs.get(n)]

    # hlsenc's reference is the pts of the first packet it receives. The muxer interleaves by dts, so
    # audio arrives first when its first dts precedes the video's (the HLS init's edit media_time is the
    # first video sample's pts - dts). An audio reference is kept in audio ticks and later subtracted in
    # the video time base, unless a video packet's raw pts is smaller (hlsenc.c 2450-2458, 2488).
    pts_raw = [Fraction(s[0], timescale) + offset for s in ref_samples]
    video_first = pts_raw[0]
    video_first_dts = video_first - Fraction(hls["edit"], timescale)
    reference, reference_from = video_first, "first video packet"
    if spec["audio"] and hls["audioFirstRaw"] is not None:
        audio_seconds = Fraction(hls["audioFirstRaw"], hls["audioTimescale"])
        misread = Fraction(hls["audioFirstRaw"], timescale)
        if audio_seconds < video_first_dts and misread <= video_first:
            reference = misread
            reference_from = (f"first audio packet: pts {hls['audioFirstRaw']} at 1/{hls['audioTimescale']} "
                              f"({float(audio_seconds):.6f} s), read by hlsenc at the video's 1/{timescale}")
    keyframes = [i for i, s in enumerate(hls["samples"]) if s[1]]
    modeled = hlsenc_cuts(keyframes, pts_raw, reference, PERIOD)
    observed = [s["ordinal"] for s in hls["segments"]]
    return {
        "hlsRun": spec["run"],
        "flags": "the HLS recipe's common flags (no -start_at_zero, -max_delay)" if spec["flags"] == "hls-recipe"
        else "the pipe recipe's common flags (-start_at_zero)",
        "streams": "video and audio" if spec["audio"] else "video only",
        "pipeReference": reference_name + ".fmp4",
        "hlsExitStatus": hls["exitStatus"],
        "frameIdentity": identity,
        "segments": converted,
        "agrees": not mismatches,
        "expectedToAgree": spec["expect"],
        "mismatches": mismatches,
        "hlsOwnTimestampsMatchPipe": direct_agrees,
        "hlsencModel": {
            "reference": reference_from,
            "referenceOnZeroBasedTimelineSeconds": round(float(reference - offset), 6),
            "reproducesHlsCuts": modeled == observed,
        },
    }


def check_source_keyframes(fixture, stream, source, timescale):
    if fixture["mode"] != "copy":
        return None
    expected = sorted({int((k - source["start"]) * timescale) for k in source["keyframes"]})
    recorded = sorted(s[0] for s in fmp4dump.video_samples(stream) if s[1])
    expected = [k for k in expected if k >= recorded[0]]
    return {"recordedKeyframesEqualSourceKeyframes": recorded == expected,
            "keyframePresentationTimes": recorded}


def diagnostics(stream):
    fragments = stream["fragments"]
    video = [fmp4dump.video_start(f) for f in fragments]
    starts = [v["firstPresentationTime"] for v in video if v]
    audio_first = next((t["baseMediaDecodeTime"] for f in fragments for t in f["trafs"] if t["handler"] == "soun"), None)
    # Where a fragment's own start + sample durations would put the next fragment, against its real start.
    duration_misses = []
    previous = None
    for v in video:
        if v is None:
            continue
        if previous is not None:
            duration_misses.append(v["firstPresentationTime"] - (previous["firstPresentationTime"] + previous["durationSum"]))
        previous = v
    samples = fmp4dump.video_samples(stream)
    sync_ordinals = {s[3] for s in samples if s[1]}
    return {
        "fragmentCount": len(fragments),
        "syncFirstFragments": sum(1 for v in video if v and v["firstSync"]),
        "nonSyncFirstFragments": sum(1 for v in video if v and not v["firstSync"]),
        "fragmentsWithoutVideo": sum(1 for v in video if v is None),
        "leadingFragmentsWithoutVideo": next((i for i, v in enumerate(video) if v is not None), len(fragments)),
        "everyKeyframeStartsAFragment": all(fmp4dump.video_start(fragments[i])["firstSync"] and
                                            fmp4dump.video_start(fragments[i])["sampleCount"] > 0
                                            for i in sync_ordinals) and
        all(sum(1 for s in samples if s[3] == i and s[1]) == 1 for i in sync_ordinals),
        "videoSamples": len(samples),
        "largestFragmentByteLength": max(f["byteLength"] for f in fragments),
        "firstVideoPresentationTime": starts[0],
        "firstAudioBaseMediaDecodeTimeUnsigned": audio_first,
        "startPlusDurationsMissesNextStartByTicks": {"min": min(duration_misses), "max": max(duration_misses)},
    }


def oracle_violations(fixture):
    name, found = fixture["name"], []
    for o in fixture["hlsOracles"]:
        if o["agrees"] != o["expectedToAgree"]:
            found.append(f"{name} / {o['hlsRun']}: agrees={o['agrees']}, expected {o['expectedToAgree']}")
        if not o["hlsencModel"]["reproducesHlsCuts"]:
            found.append(f"{name} / {o['hlsRun']}: the hlsenc model does not reproduce the HLS cuts")
    return found


def recording_violations(fixture):
    name, found = fixture["name"], []
    check = fixture["sourceKeyframeCheck"]
    if check is not None and not check["recordedKeyframesEqualSourceKeyframes"]:
        found.append(f"{name}: a recorded keyframe is not the source's own keyframe")
    if not fixture["diagnostics"]["everyKeyframeStartsAFragment"]:
        found.append(f"{name}: a keyframe does not start a fragment")
    return found


def side_claim_violations(expected):
    found = []
    found += [f"initialization segments differ: {p['label']}"
              for p in expected["initializationSegmentIdentityPairs"] if not p["identical"]]
    found += [f"initialization segments are identical: {p['label']}"
              for p in expected["initializationSegmentDifferencePairs"] if p["identical"]]
    claims = expected["adrSideClaims"]
    if claims["mpegTsAacCopyWithoutAdtstoasc"]["exitStatus"] == 0:
        found.append("an MPEG-TS AAC copy without aac_adtstoasc no longer fails")
    if not claims["adtstoascLeavesMp4SourceCopyByteIdentical"]:
        found.append("aac_adtstoasc changes a copy from an MP4 source")
    if not claims["maxDelayLeavesMp4OutputByteIdentical"]:
        found.append("-max_delay changes mp4 output")
    cfr = claims["seekWithFpsModeCfr"]
    if cfr["firstVideoPresentationTime"] != 0 or cfr["videoSamples"] <= cfr["videoSamplesWithoutFpsMode"]:
        found.append("an explicit -fps_mode cfr after a seek no longer pads from zero")
    return found


def violated_claims(expected):
    """Every claim in expected.json that the recordings contradict; any one fails the recorder."""
    found = []
    for fixture in expected["fixtures"]:
        found += oracle_violations(fixture) + recording_violations(fixture)
    return found + side_claim_violations(expected)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--work", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--image", required=True)
    args = parser.parse_args()

    ffmpeg = open(os.path.join(args.work, "ffmpeg-version.txt")).read().strip()
    pipe = {}
    for fixture in FIXTURES:
        path = os.path.join(args.work, "out", fixture["name"] + ".fmp4")
        stream = fmp4dump.read([path])
        video_track = next(t for t in stream["tracks"].values() if t["handler"] == "vide")
        pipe[fixture["name"]] = {"stream": stream, "videoTimescale": video_track["timescale"],
                                 "videoTrackId": video_track["trackId"], "path": path}

    results = []
    for fixture in FIXTURES:
        record = pipe[fixture["name"]]
        stream = record["stream"]
        source = load_source(args.work, fixture["source"])
        if fixture["mode"] == "encode" and source["video"]["r_frame_rate"] != "24000/1001":
            raise ValueError(f"{fixture['source']} probes as {source['video']['r_frame_rate']}, the script passes 24000/1001")
        delivered, preroll, failure = group(stream, PERIOD, fixture["start"])
        segments = [describe(s, stream) for s in delivered]
        oracles = [evaluate_oracle(args.work, fixture, spec, pipe, segments, source) for spec in fixture["oracles"]]
        last_video = max(i for i, f in enumerate(stream["fragments"]) if fmp4dump.video_start(f))
        results.append({
            "name": fixture["name"],
            "file": fixture["name"] + ".fmp4",
            "proves": fixture["proves"],
            "mode": fixture["mode"],
            "encoder": fixture["encoder"],
            "source": {
                "file": fixture["source"],
                "containerStartTimeMicros": source["startMicros"],
                "videoRealFrameRate": source["video"]["r_frame_rate"],
                "videoAverageFrameRate": source["video"]["avg_frame_rate"],
            },
            "seekSeconds": fixture["seek"],
            "period": PERIOD,
            "fragmentationTargetMicros": FRAGMENTATION_TARGET_MICROS,
            "startSequenceNumber": fixture["start"],
            "videoTrackId": record["videoTrackId"],
            "videoTimescale": record["videoTimescale"],
            "byteLength": os.path.getsize(record["path"]),
            "initializationSegment": {
                "byteLength": stream["initByteLength"],
                "sha256": hashlib.sha256(stream["initBytes"]).hexdigest(),
                "tracks": [{"trackId": t["trackId"], "handler": t["handler"], "timescale": t["timescale"],
                            "editList": t["edits"], "trexDefaultSampleFlags": t.get("trexFlags"),
                            "trexDefaultSampleDuration": t.get("trexDuration")}
                           for t in stream["tracks"].values()],
            },
            "segments": segments,
            "discardedPreroll": [describe(s, stream) for s in preroll],
            "failure": None if failure is None else {"reason": failure.reason, **failure.detail},
            "endsWithAudioOnlyFragments": last_video < len(stream["fragments"]) - 1,
            "trailingAudioOnlyFragmentCount": len(stream["fragments"]) - 1 - last_video,
            "sourceKeyframeCheck": check_source_keyframes(fixture, stream, source, record["videoTimescale"]),
            "diagnostics": diagnostics(stream),
            "hlsOracles": oracles,
        })

    def pair(label, a, b):
        ia, ib = pipe[a]["stream"]["initBytes"], pipe[b]["stream"]["initBytes"]
        return {"label": label, "files": [a + ".fmp4", b + ".fmp4"], "identical": ia == ib,
                "byteLengths": [len(ia), len(ib)],
                "sha256": [hashlib.sha256(ia).hexdigest(), hashlib.sha256(ib).hexdigest()]}

    def claim(name):
        folder = os.path.join(args.work, "claims")
        status = int(open(os.path.join(folder, name + ".exit-status")).read())
        log = [re.sub(r" @ 0x[0-9a-f]+", "", line.strip())
               for line in open(os.path.join(folder, name + ".log")) if line.strip()]
        data = open(os.path.join(folder, name + ".fmp4"), "rb").read()
        return status, (log[0] if log else ""), data, os.path.join(folder, name + ".fmp4")

    def recorded(name):
        return open(pipe[name]["path"], "rb").read()

    ts_status, ts_log, _, _ = claim("ts-copy-without-adtstoasc")
    _, _, mp4_copy, _ = claim("mp4-copy-without-adtstoasc")
    _, _, max_delay, _ = claim("encode-with-max-delay")
    cfr_status, _, _, cfr_path = claim("encode-seek30-fps-mode-cfr")
    cfr_samples = fmp4dump.video_samples(fmp4dump.read([cfr_path]))
    seek_samples = fmp4dump.video_samples(pipe["01-encode-cfr-seek30"]["stream"])
    claims = {
        "mpegTsAacCopyWithoutAdtstoasc": {"exitStatus": ts_status, "firstLogLine": ts_log},
        "adtstoascLeavesMp4SourceCopyByteIdentical": mp4_copy == recorded("07-copy-start0"),
        "maxDelayLeavesMp4OutputByteIdentical": max_delay == recorded("01-encode-cfr"),
        "seekWithFpsModeCfr": {
            "exitStatus": cfr_status,
            "videoSamples": len(cfr_samples),
            "firstVideoPresentationTime": cfr_samples[0][0],
            "videoSamplesWithoutFpsMode": len(seek_samples),
            "firstVideoPresentationTimeWithoutFpsMode": seek_samples[0][0],
        },
    }

    expected = {
        "recordedWith": {"image": args.image, "ffmpeg": ffmpeg},
        "recipe": "ffmpeg -y [-ss S] -i SRC -map 0:v:0 -map 0:a:0 -map_metadata -1 -map_chapters -1 -copyts "
        "-avoid_negative_ts disabled -start_at_zero -max_muxing_queue_size 128 <codec args> [-bsf:a aac_adtstoasc "
        "when copying AAC] [encode: -r:v:0 23.976023976023978 -forced-idr 1 -force_key_frames:0 expr:gte(t,n_forced*6) "
        "-g:v:0 143 (-keyint_min:v:0 143 for SVT-AV1) (-sc_threshold:v:0 0 for libx264)] -threads 1 -f mp4 -movflags "
        "cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont -frag_duration 1000000 pipe:1",
        "rules": "ADR 0037: segment N = [N*P, (N+1)*P) on the zero-based timeline. A fragment whose video traf starts "
        "with a sync sample opens segment floor(presentationTime / (P * timescale)), or joins it when that segment is "
        "already open; every other fragment joins the open segment (or waits for the first one). Segments numbered "
        "below startSequenceNumber are discarded preroll; a sync-first fragment beyond the next deliverable number "
        "fails with a skipped segment number. firstVideoPresentationTime = tfdt + first sample's composition offset, "
        "in videoTimescale ticks, no edit list.",
        "fixtures": results,
        "initializationSegmentIdentityPairs": [pair(*p) for p in INITIALIZATION_SEGMENT_IDENTITY_PAIRS],
        "initializationSegmentDifferencePairs": [pair(*p) for p in INITIALIZATION_SEGMENT_DIFFERENCE_PAIRS],
        "adrSideClaims": claims,
    }
    for fixture in FIXTURES:
        shutil.copyfile(pipe[fixture["name"]]["path"], os.path.join(args.out, fixture["name"] + ".fmp4"))
    with open(os.path.join(args.out, "expected.json"), "w") as out:
        json.dump(expected, out, indent=2)
        out.write("\n")
    violations = violated_claims(expected)
    for violation in violations:
        print("VIOLATED " + violation, file=sys.stderr)
    if violations:
        sys.exit(1)


if __name__ == "__main__":
    main()
