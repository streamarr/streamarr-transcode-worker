# Fragmented-MP4 grouping fixtures (ADR 0037, worker #39)

Recorded standard-output streams of ADR 0037's FFmpeg recipe, with the expected media segments for
the grouping rule. Each is checked against the HLS muxer's cut points on the same source.
`RecordedFfmpegOutputTest` runs the real `FragmentedMp4Reader` and `SegmentGrouper` over every
recording and asserts `expected.json`, so the reader and grouper are pinned to the worker's own
FFmpeg output (ADR "Tests substitute the process"). `FfmpegRecordings` loads a recording and its
expectations for any test, such as one that replays the bytes through a scripted `Process`.

| File | What it is |
|---|---|
| `NN-*.fmp4` | A recorded `pipe:1` stream: `ftyp` + `moov`, then `moof` + `mdat` fragments, exactly as FFmpeg wrote them |
| `expected.json` | Per fixture: expected segments, discarded preroll, skip failure, audio-only tail, init digest, HLS oracle comparison; plus init identity pairs and ADR side claims |
| `../../fmp4-recorder/record-fixtures.sh` | Regenerates everything from scratch in the pinned worker image (about 15 s) |
| `../../fmp4-recorder/analyze.py` | Groups the recordings by the ADR rules, evaluates the HLS oracles, writes `expected.json` |
| `../../fmp4-recorder/fmp4dump.py` | Standalone box reader (`python3 fmp4dump.py FILE`), independent of the worker's Java code |

The recorder lives in `src/test/fmp4-recorder`, outside the test classpath; only the recordings,
`expected.json` and this file are test resources.

## Regenerating

From the repository root (Docker, and python3 with the standard library only):

```
src/test/fmp4-recorder/record-fixtures.sh                                # re-record in place
WORK=/tmp/fx src/test/fmp4-recorder/record-fixtures.sh                   # keep sources, FFmpeg logs and HLS oracle outputs
WORKER_IMAGE=streamarr-worker:local src/test/fmp4-recorder/record-fixtures.sh   # record with another worker image
```

The script writes the recordings and `expected.json` into this directory, so `git diff` shows
what a re-recording changed and `RecordedFfmpegOutputTest` then checks the reader and grouper
against it. Re-record after an FFmpeg lock update with an image built from that lock (see
[Image validation](../../../../docs/image-validation.md)), and review every changed expectation.

Every FFmpeg and ffprobe run happens inside
`streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:9d2d286c…caa`
(FFmpeg 8.1.2-Jellyfin, SVT-AV1 v4.1.0) unless `WORKER_IMAGE` names another; `expected.json`
records the image. Homebrew's FFmpeg is never used. Sources are synthesized with lavfi (`testsrc`
64x36, silent mono AAC at 48 kHz) and are not delivered. Two complete recordings in separate
directories produced byte-identical streams, sources and `expected.json`.

## Recipe

ADR 0037 / DESIGN.md "Worker command recipe", with P = 6 s, a 1 s fragmentation target and the
probed frame rate passed as the worker would pass it (`r_frame_rate` 24000/1001 as a Java double):

```
ffmpeg -y [-ss 30] -i SRC -map 0:v:0 -map 0:a:0 -map_metadata -1 -map_chapters -1
  -copyts -avoid_negative_ts disabled -start_at_zero -max_muxing_queue_size 128
  copy:   -c:v copy -c:a copy -bsf:a aac_adtstoasc
  x264:   -c:v libx264 -vf scale=-2:36 -b:v 6000 -maxrate 6000 -bufsize 12000 -c:a aac -ac 1 -b:a 8k
          -r:v:0 23.976023976023978 -forced-idr 1 -force_key_frames:0 expr:gte(t,n_forced*6)
          -g:v:0 143 -sc_threshold:v:0 0
  svtav1: -c:v libsvtav1 -vf scale=-2:36 -crf 35 -maxrate 6000 -svtav1-params mbr-overshoot-pct=0:lp=1
          -c:a aac -ac 1 -b:a 8k
          -r:v:0 23.976023976023978 -forced-idr 1 -force_key_frames:0 expr:gte(t,n_forced*6)
          -g:v:0 143 -keyint_min:v:0 143
  -threads 1
  -f mp4 -movflags cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont -frag_duration 1000000 pipe:1
```

Fixture-only additions are `-threads 1`, and `lp=1` for SVT-AV1, whose output otherwise differs on
every run. Neither moves a keyframe or a cut. Nothing else differs from the recipe.

## Conventions in expected.json

- `firstVideoPresentationTime` is in ticks of `videoTimescale`. It is the first video sample's
  presentation time: `tfdt` baseMediaDecodeTime plus that sample's signed `trun` composition offset,
  with no edit list, on the zero-based timeline that `-start_at_zero` produces.
- `segments` is what the grouper must deliver. Each entry is `number`, `firstVideoPresentationTime`,
  `firstFragmentIndex`, `fragmentCount`, `syncFirstFragmentCount` and `byteLength` (the sum of that
  segment's `moof`+`mdat` bytes, concatenated in arrival order). Fragment indexes count fragments
  after the initialization segment, from 0.
- `discardedPreroll` lists segments numbered below `startSequenceNumber`, which are never delivered.
  `failure` names the named-reason failure (fixture 10) and the segments delivered before it.
- `endsWithAudioOnlyFragments` / `trailingAudioOnlyFragmentCount` describe fragments with no video
  `traf` after the last video sample.
- `hlsOracles[]` holds the HLS muxer's segment starts mapped onto this recording, whether they agree,
  and `hlsencModel` (see below). `sourceKeyframeCheck` (copy runs) confirms that every recorded
  keyframe is the source's own keyframe at its source time minus the container start.

## Fixtures

Timescale 24000 unless stated. `n@t` means segment n starts at t seconds.

| # | File | Start seq. | Proves |
|---|---|---|---|
| 1 | `01-encode-cfr.fmp4` | 0 | Constant 23.976 fps libx264, 66 s. 11 segments: 0@0, 1@6.006 … 7@42.0003 … 10@60.018. The 143-frame GOP puts a keyframe one frame before 9 of the 10 later forced keyframes (5.964, 11.970 …), and on the last frame (65.983 s). Each is a 1-frame sync-first fragment inside the earlier interval, so it joins the earlier segment. |
| 1b | `01-encode-cfr-seek30.fmp4` | 5 | Encoded seek: starts at 30.030 s (segment 5), 863 frames, nothing padded from zero, no preroll. Segments 5–10 start on exactly the ticks of the start-0 run's segments 5–10. Ends with one audio-only fragment. |
| 2 | `02-copy-irregular-keyframes.fmp4` | 0 | Timescale 12288 (24 fps). Keyframes only at 0, 6.5, 12, 18, 24.5, 30, 36.5, 42, 48, 54.5, 60 s. 11 segments, one keyframe each. Keyframes exactly on a boundary (12, 18, 30, 42, 48, 60 s) open that boundary's segment. |
| 3 | `03-encode-vfr.fmp4` | 0 | VFR source (1068 frames, avg 16.2 fps on the 23.976 grid, first frame at 41.7 ms) encoded under `-r`: 1582 constant-rate frames, the same count as today's HLS run. A keyframe in every interval, 11 segments. |
| 4 | `04-copy-vfr-bframes.fmp4` | 0 | VFR stream copy with B-frames and one or two keyframes per interval. Keyframes at 5.922, 29.988, 47.922 and 59.976 s, just before a boundary, stay in the earlier segment. The keyframe meant for 17.95 s landed at 18.017 s (dropped frames) and opens segment 3. |
| 5a | `05-encode-late-start.fmp4` | 0 | MPEG-TS source whose timestamps begin at 12 s. The container start is the AAC priming frame (11.978667 s), so video starts at 0.0417 s (encode): segment 0, not 2. The last output frame (30.030 s) is a GOP keyframe and forms a 1-frame segment 5 (see findings). |
| 5b | `05-copy-late-start.fmp4` | 0 | Timescale 90000. The same source copied with `-bsf:a aac_adtstoasc`: video starts at 0.0213 s, keyframes every 2.002 s, 5 segments. |
| 6 | `06-encode-audio-tail.fmp4` | 0 | 20 s video, 23.5 s audio. The run ends in 4 audio-only fragments (19.95, 20.95, 21.95, 22.95 s), which join segment 3. No fragment without video precedes the first segment. |
| 7 | `07-copy-start0.fmp4` | 0 | 2.002 s-GOP 23.976 fps copy from the start: 11 segments of 3 keyframes each. |
| 7b | `07-copy-seek30.fmp4` | 5 | Stream-copy restart at `-ss 30`. The seek lands on the keyframe at 28.028 s (segment 4 < 5). That keyframe fragment and the non-sync fragment after it (2 fragments) are discarded preroll. Segments 5–10 carry exactly 7's video samples at the same ticks. Their audio is the same packets at the same times, but each fragment starts one AAC frame earlier (see findings). |
| 8 | (pairs over 1, 7, 9) | – | `initIdentityPairs`: `ftyp`+`moov` is byte-identical between the start-0 and seek runs for libx264 (1348 B), stream copy (1348 B) and SVT-AV1 (1334 B). `initDifferencePairs`: encode and copy inits of the same source differ. Each init has a zero edit list and zero `trex` defaults. |
| 9 | `09-svtav1-vfr.fmp4` | 0 | The VFR source through SVT-AV1 (143-frame GOP + time-based forced keyframes): 1582 frames, a keyframe in every interval, segment starts on exactly the ticks of 3 (libx264). SVT-AV1 honours the forced keyframe. No B-frame reordering, so composition offsets are 0. |
| 9b | `09-svtav1-vfr-seek30.fmp4` | 5 | The same after `-ss 30`: starts at 30.0717 s (the first source frame after 30 s), 862 frames, no padding, no preroll. |
| 10 | `10-copy-gop-exceeds-period.fmp4` | 0 | Extra, not in the assignment. A copy with a keyframe every 10.01 s. Segment 0 is delivered. The sync-first fragment at 20.02 s (fragment 20) is segment 3 while 2 is expected: `skipped segment number`. |

Box facts every recording shares (useful for the reader):
- Top-level boxes are only `ftyp`, `moov`, `moof` and `mdat`, in that order, with no `largesize`, `styp` or `sidx`.
- `moov` holds `mvhd`, two `trak`s (video track 1, audio track 2), `mvex` and `udta`.
- Every `traf` has `tfhd` flags `0x02003a` (default-base-is-moof plus default duration, size and flags), `tfdt` version 1 and exactly one `trun`.
- Every `trun` is version 1 (signed composition offsets) with a data offset. Sync status is carried by `trun` first_sample_flags (0x4) when present, otherwise by the `tfhd` default flags. Per-sample flags (0x400) never occur.
- The largest fragment is 3.4 KB; every keyframe starts a fragment.
- The first audio `tfdt` is 2^64 − 1024: the unsigned wrap of −1024, the AAC priming.

## The HLS oracle, and where it disagrees

For every fixture the same source was also run through the HLS muxer (`-f hls -hls_segment_type fmp4`):
- `*.hls-recipe`: the HLS muxer recipe that ADR 0037 replaces, as `FfmpegCommandBuilder` builds it (no `-start_at_zero`, `-max_delay`, its keyframe arguments, with audio).
- `*.video-only`: the pipe recipe's own flags and keyframe arguments, video only.

Each HLS segment's first video sample is mapped to the pipe recording by its ordinal in decode order.
Frame identity is checked by equal sample counts in every oracle run, and by identical sample sizes
wherever the two runs share their video arguments. The HLS files' own timestamps are not usable
directly. With `frag_discont`, movenc "pretends the stream started at pts=0" (movenc.c 7091) and snaps
each later fragment's dts to the running duration sum. So HLS segment timestamps lose the start
offset (5a, 5b) and drift by a frame on the VFR B-frame copy (4).

Agreement, exact in ticks:

| Fixture | hls-recipe | video-only | Note |
|---|---|---|---|
| 1, 2, 6, 7 | agrees | agrees | |
| 5b | agrees (with `aac_adtstoasc` added; the HLS recipe's exact copy exits 255) | agrees | |
| 7b | agrees with the start-0 run's HLS output | – | its own HLS run is misaligned: 28.028, 34.034, 40.040 … as ADR 0037 describes |
| 1b | agrees with the start-0 run's HLS output | differs on 7–10 by one frame | see below |
| 3, 5a, 9, 9b | differs by one frame | differs by one frame | see below |
| 4 | differs on segment 3 | differs on segment 3 | see below |
| 10 | differs | – | the HLS muxer numbers sequentially and never skips |

Every disagreement is explained by where hlsenc measures from, not by the grouping. `hlsencModel`
re-implements hlsenc.c's cut rule (FFmpeg n8.1 source, lines 2440–2489):
- the first packet opens segment 0;
- a later keyframe cuts when `pts − start_pts ≥ hls_time × number` in the video time base.

It applies that rule to the HLS run's own keyframes, with `start_pts` = the pts of the first packet
the muxer receives. The model reproduces the observed HLS cut list in all 28 oracle runs
(`reproducesHlsCuts: true`). Its reference differs from the grid's zero in three ways:

1. **First video frame after zero** (3, 5a, 9: video starts 41.7 ms after the container start).
   hlsenc cuts at the first keyframe at or after N·P + 41.7 ms. The 143-frame GOP puts a keyframe at
   N·P + 6 ms (6.006 s), inside interval N, so the grid opens segment N there. hlsenc instead cuts at
   the forced keyframe one frame later (6.048 s). In 4, the source keyframe at 18.017 s lies inside
   [18, 18.041), so hlsenc cuts one keyframe later (19.018 s).
2. **Seek runs** (1b, 9b, 7b). hlsenc measures from the seek run's first frame (30.030 s, 30.072 s,
   28.028 s), not from the grid. In 1b the GOP keyframe at 42.0003 s (frame 1007) lies in interval 7,
   so the grid opens segment 7 there (as the start-0 run does). hlsenc cuts at the forced keyframe
   at 42.042 s. For 7b this is exactly the ADR's replacement misalignment.
3. **An audio reference read in the video time base.** When the first audio dts precedes the first
   video dts, `start_pts` is the audio packet's pts in audio ticks, yet hlsenc subtracts it in the
   video time base. It replaces it only if a video packet's raw pts is smaller (hlsenc.c 2450–2458).
   - With 48 kHz audio and libx264 B-frames, video comes first (dts −83 ms against −21 ms).
   - With SVT-AV1 (dts = pts), audio comes first: the reference is −1024 read at 1/24000 = −42.7 ms.
   - In an earlier 8 kHz-audio recording (not kept), audio came first for libx264 too. hlsenc then cut
     at the GOP keyframe one frame before each boundary, and after a seek it cut at every keyframe.

   This also affects today's production fMP4 HLS variants (HEVC/AV1). In MPEG-TS HLS every stream
   shares 1/90000, so the misread should cancel there (not tested).

## ADR 0037 claims checked on the pinned FFmpeg

Reproduced:
- Initialization segment:
  - `ftyp`+`moov` is byte-identical across start 0 and a seek, for encode and for stream copy (and SVT-AV1).
  - It has a zero edit list and zero `trex` defaults.
  - Encode and copy inits differ.
- Fragmentation:
  - With no `min_frag_duration` and `-frag_duration` 1 s, every keyframe starts a fragment.
  - Every fragment that does not start at a keyframe starts with a non-sync sample.
- Timestamps and grouping:
  - The `cmaf` flag writes negative composition offsets.
  - The first audio `tfdt` is the unsigned wrap of −1024.
  - A fragment's start plus its sample durations misses the next fragment's start by up to ±4004 ticks
    (167 ms). A fragment's end cannot be computed from its durations.
  - Fragments without video appear only after the last video sample: one per target of audio that
    outlasts the video (4 for 3.5 s). None ever precedes the first segment.
  - On a 10 s-GOP copy the sync-first fragments fall in segments 0, 1, 3; the first skip is at 20.02 s.
- Start point and seeking:
  - `-start_at_zero` on an MPEG-TS source puts zero at the AAC priming frame. Video starts at
    0.021 s (copy) and 0.042 s (encode), exactly the ADR's numbers.
  - An encoded seek to 30 s starts at 30.030 s. A stream-copy seek starts at 28.028 s, which is preroll.
  - Today's HLS muxer starts a stream-copy replacement's segments at 28.028, 34.034 and 40.040 s.
- Frame rate:
  - `-r` with no `-fps_mode` yields the same constant-rate frames as today's HLS muxer on a VFR
    source (1582 = 1582).
  - After a seek it pads nothing. An explicit `-fps_mode cfr` after `-ss 30` pads from zero
    (1583 frames starting at 0, against 863 starting at 30.030 s).
- SVT-AV1:
  - It honours the time-based forced keyframe and places keyframes on exactly the frames libx264
    chose (9 = 3 tick for tick).
  - It has a keyframe in every interval after a seek, with no padding.
- Audio and muxer flags:
  - An MPEG-TS AAC copy without `-bsf:a aac_adtstoasc` exits 255 with "Malformed AAC bitstream
    detected".
  - The filter leaves an MP4-source copy byte-identical.
  - `-max_delay 5000000` leaves mp4 output byte-identical.

Did not reproduce exactly (mechanism in each case; none is a grouping error):
- **"Grouping reproduced the HLS muxer's cut points … 11 into 11 for the variable-frame-rate
  encode."** On this VFR encode (3) the count is 11 and 11, but 9 of the 11 segment starts are one
  frame (41.7 ms) earlier than the HLS muxer's. Two things combine:
  - the new 143-frame GOP adds a keyframe at N·P + 6 ms;
  - hlsenc measures from the first video frame at 41.7 ms (reference 1 above).
- **"At 23.976 fps for encode, stream copy and a seek the two rules agree."** Encode and copy agree
  exactly. The encoded seek (1b) disagrees with its own HLS run on segments 7–10 by one frame, but
  agrees tick for tick with the start-0 run. With `-g 143`, libx264's GOP keyframe at 42.0003 s falls
  inside interval 7, while hlsenc, measuring from 30.030 s, waits for 42.042 s.
- **"… including the one-frame-early 42.000 s."** This holds on a source whose first frame is at 0
  (1: 42.0003 s). On the VFR source, whose first frame is at 41.7 ms, `force_key_frames`' `t` puts
  interval 7's forced keyframe at 42.042 s for both encoders.
- **The ADR's replacement numbers (28.028, 34.034, 40.040 s)** reproduce only because the fixtures
  use 48 kHz audio. With 8 kHz audio, hlsenc's audio reference made it cut at every keyframe
  (reference 3).
- Not measured here: the 1000-segment run per software encoder, `min_frag_duration` behaviour, MPEG-TS
  input-seek behaviour, MKV sources, the demuxer's 2002-tick presentation delay, and the fragment spans
  (1.001 s / 1.033 s).

## Findings for the producer and the server

- **libx264 and SVT-AV1 with both the floored GOP and forced keyframes emit an extra IDR per segment.**
  At 23.976 fps the forced keyframes are 143 or 144 frames apart. Whenever the gap is 144, the
  143-frame GOP inserts an IDR one frame earlier: 9 of 10 boundaries in 1. Grouping handles it (the
  1-frame sync-first fragment joins the earlier segment when it falls before N·P). It still costs an
  extra IDR and a tiny fragment per segment. When the stream starts after zero, the GOP keyframe
  lands just after N·P and opens the segment one frame before the forced keyframe.
- **An encode can end in a 1-frame segment past the source's content** (5a). The 30 s source's last
  constant-rate frame, at 30.030 s, is a GOP keyframe, so the producer closes a segment 5 holding one
  frame. Whether the server advertises segment 5 depends on how it rounds the probed duration. An
  upload of a number it does not advertise must not fail the attempt.
- **A stream-copy restart repeats one AAC frame at the splice** (7b). The restart's fragments carry
  the same audio packets at the same times, but each starts one packet (1024 ticks, 21.3 ms) earlier
  than the original run's. So the replacement's segment 5 begins with the original segment 4's last
  AAC frame: 21.3 ms of audio heard twice, not a gap. Video is identical.
- **Trailing audio-only fragments are common, not exceptional.** With 48 kHz audio they end 1b, 2, 4,
  5b and 10, as well as 6.
- `hlsenc` measures fMP4 cut points in the video time base from a reference that can be an audio
  packet's pts. That is a latent defect of today's fMP4 HLS path (reference 3 above).
