# Fragmented-MP4 grouping fixtures (ADR 0037, worker #39)

Recorded standard-output streams of ADR 0037's FFmpeg recipe, with the media segments that the
zero-based grid rule groups them into. The recordings pin that rule. Every fixture also runs the same
source through FFmpeg's HLS muxer as differential evidence, not as an oracle: each HLS comparison
in `expected.json` records every segment whose cut point differs from the grid, and this file
explains the cause.
`RecordedFfmpegOutputTest` runs the real `FragmentedMp4Reader` and `SegmentGrouper` over every
recording and asserts `expected.json`, so the reader and grouper are pinned to the worker's own
FFmpeg output (ADR "Tests substitute the process"). `ProducerTest` replays every recording through
the real producer, which rebuilds each media segment the grouper closes as one `moof` and one
`mdat` and places each audio sample in the segment whose interval holds its start, and checks the
delivered samples against the recording's own, sample by sample. `FfmpegCommandBuilderRecipeTest` builds the
worker's FFmpeg command for every recording that follows the recipe (no `recipeDeviation`) and
requires it to equal that recording's `ffmpegArguments` without the fixture-only additions, so the
recordings and the worker's command cannot drift apart. `FfmpegRecordings` loads a recording and its
expectations for any test, such as one that replays the bytes through a scripted `Process`.

| File | What it is |
|---|---|
| `NN-*.fmp4` | A recorded `pipe:1` stream: `ftyp` + `moov`, then `moof` + `mdat` fragments, exactly as FFmpeg wrote them |
| `expected.json` | Per fixture: expected segments, discarded preroll, skip failure, audio-only tail, initialization-segment digest, HLS comparisons; plus initialization-segment identity pairs and ADR side claims |
| `../../fmp4-recorder/record-fixtures.sh` | Regenerates everything from scratch in the pinned worker image (about 15 s) |
| `../../fmp4-recorder/analyze.mjs` | Groups the recordings by the ADR rules (`grid.mjs`), compares the HLS muxer runs with the grid (`analysis.mjs`), writes `expected.json` |
| `../../fmp4-recorder/fmp4.mjs` | Standalone box reader (`node fmp4.mjs FILE`), independent of the worker's Java code |
| `../../fmp4-recorder/check-expectations.mjs` | Offline drift check: re-derives `expected.json` from the committed recordings (no Docker) |

The recorder lives in `src/test/fmp4-recorder`, outside the test classpath; only the recordings,
`expected.json` and this file are test resources. It is plain Node (the major in
`buildpacks/ffmpeg/.nvmrc`) with no packages, and keeps every timestamp exact: BigInt ticks and
BigInt rationals (`rational.mjs`), never a double. `node --test src/test/fmp4-recorder/*.test.mjs`
tests the box reader, the grid model and the analysis arithmetic.

## Checking offline

CI's tooling job runs the recorder's node tests and
`node src/test/fmp4-recorder/check-expectations.mjs`, without Docker or FFmpeg. The check re-reads
every committed `.fmp4`, re-derives with the grid model everything that a recording's own bytes
decide (tracks, initialization segment, the initialization segment that the producer delivers,
media segments, preroll, failure, audio-only tail, diagnostics, a copy's keyframes, each HLS
comparison's disagreements with the grid, the initialization-segment pairs), and fails on every fact `expected.json` states differently, on a
recording it does not describe, and on a described recording that is missing. The HLS muxer's own
cuts, the sources, each recording's FFmpeg arguments and the ADR side claims need a recording run,
so it takes those as recorded, and then fails on every claim of the recorder (see
[Regenerating](#regenerating)) that the facts in `expected.json` contradict.

## Regenerating

From the repository root (Docker, and Node with no packages):

```
src/test/fmp4-recorder/record-fixtures.sh                                # re-record in place
WORK=/tmp/fx src/test/fmp4-recorder/record-fixtures.sh                   # keep sources, FFmpeg logs and HLS muxer outputs
WORKER_IMAGE=streamarr-worker:local src/test/fmp4-recorder/record-fixtures.sh   # record with another worker image
```

The script writes the recordings and `expected.json` into this directory, so `git diff` shows
what a re-recording changed and `RecordedFfmpegOutputTest` then checks the reader and grouper
against it. Re-record after an FFmpeg lock update with an image built from that lock (see
[Image validation](../../../../docs/image-validation.md)), and review every changed expectation.
After writing, the recorder exits with status 1 and names each claim a recording contradicts: an
HLS comparison's expected agreement, the hlsenc model, a copy's source keyframes, a keyframe that does
not start a fragment, a verified encoder's keyframe that does not start a closed GOP, the
initialization-segment pairs, or an ADR side claim.

Every FFmpeg and ffprobe invocation happens inside
`streamarr/streamarr-transcode-worker:0.1.0-SNAPSHOT@sha256:9d2d286c…caa`
(FFmpeg 8.1.2-Jellyfin, SVT-AV1 v4.1.0) unless `WORKER_IMAGE` names another; `expected.json`
records the image. Homebrew's FFmpeg is never used. Sources are synthesized with lavfi (`testsrc`
64x36, silent mono AAC at 48 kHz) and are not delivered. Two complete recordings in separate
directories produced byte-identical streams, sources and `expected.json`.

## Recipe

ADR 0037's Decision ("One producer per job attempt reads FFmpeg's standard output"), with P = 6 s, a 1 s fragmentation target and the
probed frame rate passed as the worker would pass it (`r_frame_rate` 24000/1001 as a Java double):

```
ffmpeg -hide_banner -nostdin -loglevel error
  -y [-ss S] -i SRC -map 0:v:0 -map 0:a:0 -map -0:s -map_metadata -1 -map_chapters -1
  -copyts -avoid_negative_ts disabled -start_at_zero -max_muxing_queue_size 128
  -max_interleave_delta 10000000
  copy:   -c:v copy -bsf:v h264_mp4toannexb -tag:v avc1 -strict unofficial
          -c:a copy -bsf:a aac_adtstoasc -b:a 0
  copy with encoded audio (16): -c:v copy -bsf:v h264_mp4toannexb -tag:v avc1 -strict unofficial
          -c:a aac -ac 1 -b:a 8k
  x264:   -c:v libx264 -preset faster -tag:v avc1 -profile:v high -level:v 10
          -vf scale=-2:36,format=yuv420p -b:v 6000 -maxrate 6000 -bufsize 12000 -c:a aac -ac 1 -b:a 8k
          -r:v:0 23.976023976023978 -forced-idr 1 -force_key_frames:0 K*6,(K+1)*6,...,(N-1)*6
          -sc_threshold:v:0 0 -g:v:0 145                      (fixture 11: -g:v:0 143)
  svtav1: -c:v libsvtav1 -preset 10 -level:v 20 -vf scale=-2:36,format=yuv420p -crf 35 -maxrate 6000 -svtav1-params mbr-overshoot-pct=0:lp=1
          -c:a aac -ac 1 -b:a 8k
          -r:v:0 23.976023976023978 -forced-idr 1 -force_key_frames:0 K*6,(K+1)*6,...,(N-1)*6
          -g:v:0 145 -keyint_min:v:0 145
  x265:   -c:v libx265 -preset veryfast -tag:v hvc1 -profile:v main
          -vf scale=-2:36,format=yuv420p -b:v 6000 -maxrate 6000 -bufsize 12000 -c:a aac -ac 1 -b:a 8k
          -r:v:0 23.976023976023978 -forced-idr 1 -force_key_frames:0 K*6,(K+1)*6,...,(N-1)*6
          -x265-params no-scenecut=1:no-open-gop=1:level-idc=10:high-tier=0:pools=none:frame-threads=1:log-level=error
          -g:v:0 145                                           (fixture 13c: -g:v:0 143)
  -threads 1
  -f mp4 -movflags cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont -frag_duration 1000000 pipe:1
```

Each encode forces the codec profile, tier and level that the job carries, as the worker does. For
the 64x36 sources at 23.976 fps they are the values each encoder chooses when nothing is forced:
H.264 High at level 1.0 (`avc1.64000A`), AV1 Main at level 2.0 (`av01.0.00M.08`), and H.265 Main at
level 1.0 in the Main tier. Forcing them left the libx264 and SVT-AV1 recordings byte-identical.

The H.264 and HEVC output carries the playable sample entry of ADR 0013's tag rule: `avc1` and
`hvc1`. FFmpeg writes `avc1` for libx264 anyway, so the tag left the libx264 recordings
byte-identical. Each copy passes `h264_mp4toannexb`, which puts the source's SPS and PPS before
every keyframe in the samples; the MPEG-TS copy already carried them, so its recording did not
change. The HLS recipe's copies take the same video arguments, so that both recipes hold the same
packets.
libx265 writes its parameters into the initialization segment, so its recordings grew by one byte.

The producer removes the SEI messages from every `hvc1` record before it delivers the
initialization segment. FFmpeg builds the record from the first sample when the source's record
holds no parameter sets, and then copies that sample's SEI messages into it. Attempts that start at
different keyframes would deliver different initialization segments, which ADR 0037 forbids. The
samples keep their SEI messages. `deliveredInitializationSegment` in `expected.json` gives the
length and SHA-256 of the initialization segment that the producer delivers. It differs from the
recorded one only for the libx265 recordings, whose record holds libx265's settings as an SEI
message: 1,421 bytes instead of 3,848.

An encode forces its keyframes with FFmpeg's list mode: every segment boundary k × 6 in whole
seconds, for each k from the attempt's start sequence number K up to, not including, the media
segment count N that the server advertises (`mediaSegmentCount`). FFmpeg compares each frame's own
timestamp with those absolute media times, so it forces the first frame at or after each boundary,
and every attempt forces the same frame at a boundary it shares with another attempt. The recorder
derives N as the server does: the probed duration in whole milliseconds over the period, rounded up.
That is 12 for `cfr.mp4` (66.024 s), 11 for `vfr.mp4` and `irregular.mp4` (66 s), 6 for `late.ts`
(30.051 s), 5 for `gop10.mp4` (25.025 s), 4 for `tail.mp4` (23.5 s) and `short-audio.mp4` (20.02 s),
31 for `long-video.mp4` (180.013 s), and 5 for the first 30 s that fixtures 11, 12, 12b, 13b and 13c
read. A start-0 encode of `cfr.mp4` therefore receives `0,6,…,66` and its replacement attempts 1b and
13d `30,36,…,66`; a time past the last video frame forces nothing.

A replacement attempt from segment K > 0 is a job that seeks to K × 6 (`seekSeconds`, 30 s for 1b,
7b, 9b and 13d). A stream copy passes that seek to FFmpeg (`-ss 30`) and lands on the keyframe at or before
it. An encode seeks one period earlier, to (K − 1) × 6 (`-ss 24`), and the grouper discards that
period, segment K − 1, as preroll, exactly as it discards a stream copy's.

The frame-count GOP follows ADR 0037's amended ceiling: 145 = ceil(6 × 23.976) + 1 for an encoder
verified to honour time-based forced keyframes and to restart its GOP count at each one (libx264,
SVT-AV1 and libx265; see [Verified encoders](#verified-encoders)), and 143 = floor(6 × 23.976) for
one that is not, which fixtures 11 (libx264) and 13c (libx265) stand in for. Fixtures 11, 12, 12b,
13b and 13c read only the source's first 30 s (input `-t 30`), and fixtures 12, 12b and 13b leave 18
out of the list (`0,6,12,24`), so the forced keyframe for 18 s
never comes.

Fixture-only additions are `-hide_banner -nostdin -loglevel error`, which keep FFmpeg's log quiet
and its input closed, `-threads 1`, `lp=1` for SVT-AV1, whose output otherwise differs on every
invocation, and the keys `pools=none:frame-threads=1:log-level=error` for libx265, which keep it
single-threaded and quiet too. FFmpeg keeps only the last `-x265-params` option, so the recorder
appends these keys to the worker's own `no-scenecut=1:no-open-gop=1:level-idc=10:high-tier=0` instead of passing a second
option. For libx264 and libx265 none moves a keyframe or a cut. For SVT-AV1,
`lp=1` also excludes [upstream issue 2385](https://gitlab.com/AOMediaCodec/SVT-AV1/-/work_items/2385)
(worker #42): under the worker's threading the pinned SVT-AV1
can emit packets out of decode order, which leaves one-tick video samples and crowds keyframes into
one or two intervals, so no recording here shows that bug. Nothing else differs from the recipe,
apart from what a fixture's `recipeDeviation` names (fixtures 11, 12, 12b, 13b and 13c), and the arguments come in the
order the worker's command builder passes them.

## Conventions in expected.json

- `firstVideoPresentationTime` is in ticks of `videoTimescale`. It is the presentation time of the
  keyframe that opens the segment: `tfdt` baseMediaDecodeTime plus the durations of the samples
  before it in its fragment plus its signed `trun` composition offset, with no edit list, on the
  zero-based timeline that `-start_at_zero` produces.
- `segments` is what the grouper must deliver. Each entry is `number`, `firstVideoPresentationTime`,
  `firstFragmentIndex`, `fragmentCount`, `keyframeCount` (the keyframes among the segment's own
  video samples) and `byteLength` (the sum of the `moof`+`mdat` bytes of the fragments that carry
  its samples, concatenated in arrival order). Fragment indexes count fragments after the
  initialization segment, from 0. A segment opens at its keyframe even inside a fragment; the
  samples before that keyframe belong to the segment before, so that fragment is counted in both
  (16b). The producer delivers the segment's own video samples, from its opening keyframe to the
  next segment's, rebuilt as one `moof` and one `mdat`; its audio samples follow their own start
  instead.
- `ffmpegArguments` is the recording run's FFmpeg command line exactly as it ran, one argument per
  entry, fixture-only additions included (see [Recipe](#recipe)).
- `seekSeconds` is the job's seek position, the start sequence number times the period; an encode's
  FFmpeg seek is one period earlier (see [Recipe](#recipe)).
- `mediaSegmentCount` is the media segment count the server advertises for what the recording read.
  An encode forces its keyframes at k × `period` for every k from `startSequenceNumber` up to it.
- `recipeDeviation` says how a fixture deliberately departs from the worker's recipe, and is null
  when it follows the recipe.
- `discardedPreroll` lists segments numbered below `startSequenceNumber`, which are never delivered.
  `failure` names the named-reason failure (fixture 10) and the fragment that fails; `segments` then
  lists what is delivered before the failure. A keyframe that skips a segment number still marks the
  end of the open segment, so that segment is complete: it is delivered (or discarded, when it is
  preroll) and the attempt then fails. The skipping keyframe and everything after it are never
  grouped. Whether a segment that long may be served at all is a duration policy that
  [server #66](https://github.com/streamarr/streamarr-server/issues/66) owns, not the grouping.
- `mode` and `audio` say whether the recording encodes or copies its video and its audio.
- `endsWithAudioOnlyFragments` / `trailingAudioOnlyFragmentCount` describe fragments with no video
  `traf` after the last video sample.
- `diagnostics.fragmentsWithAKeyframeInside` lists the fragments that hold a keyframe after their
  first video sample. Only the first fragment may: `delay_moov` writes nothing until every track has
  a sample (16b).
- `diagnostics.videoPictures.keyframes` lists every keyframe in decode order with its presentation
  time, the picture that starts it, and whether that picture starts a closed GOP. For H.264 and HEVC
  the picture is the NAL unit type of the keyframe's first picture (5: H.264 IDR; 19 or 20: HEVC
  IDR; 21: HEVC CRA, which opens a GOP), and an HEVC keyframe also counts the RASL pictures (which
  reference the GOP before a CRA) that follow it in decode order. For AV1 it is the frame type of
  the sample's first frame header and whether that frame is shown.
- `hlsComparisons[]` holds the HLS muxer's segment starts mapped onto this recording, whether they agree,
  `frameIdentity` (how many video packets equal the recording's, and whether the run shares its
  video arguments) and `hlsencModel` (see below). `sourceKeyframeCheck` (copy recordings) confirms that every recorded
  keyframe is the source's own keyframe, at a media time equal to its source timestamp minus the
  container start.

## Fixtures

Timescale 24000 unless stated. `n@t` means segment n starts at t seconds.

| # | File | Start seq. | Proves |
|---|---|---|---|
| 1 | `01-encode-cfr.fmp4` | 0 | Constant 23.976 fps libx264, 66 s, under the verified GOP of 145 frames. 11 segments: 0@0, 1@6.006 … 7@42.0003 … 10@60.018. The only keyframes are the forced ones, the first frame at or after each boundary (frames 0, 144, 288 … 864, 1007, 1151, 1295, 1439), so the GOP never fires and every segment holds one keyframe-first fragment. The list ends at 66 s, which no frame reaches. |
| 1b | `01-encode-cfr-seek30.fmp4` | 5 | Encoded replacement attempt from segment 5, seeking one period early (`-ss 24`): starts at 24.024 s, 1007 frames, nothing padded from zero. Segment 4 (the first frame, a keyframe, and 5 non-sync fragments) is discarded preroll. The list `30,36,…,66` forces the frames 1 forced (720, 864, 1007, 1151, 1295, 1439), so every delivered segment, 5–10, starts on exactly 1's ticks. Ends with one audio-only fragment. |
| 2 | `02-copy-irregular-keyframes.fmp4` | 0 | Timescale 12288 (24 fps). Keyframes only at 0, 6.5, 12, 18, 24.5, 30, 36.5, 42, 48, 54.5, 60 s. 11 segments, one keyframe each. Keyframes exactly on a boundary (12, 18, 30, 42, 48, 60 s) open that boundary's segment. |
| 3 | `03-encode-vfr.fmp4` | 0 | VFR source (1068 frames, avg 16.2 fps on the 23.976 grid, first frame at 41.7 ms) encoded under `-r`: 1582 constant-rate frames, the same count as the HLS recipe's output. The list forces the first frame at or after each boundary (6.006, 12.012 … s, the same ticks as 1), exactly one keyframe in every interval, 11 segments. |
| 4 | `04-copy-vfr-bframes.fmp4` | 0 | VFR stream copy with B-frames and one or two keyframes per interval. Keyframes at 5.922, 29.988, 47.922 and 59.976 s, just before a boundary, stay in the earlier segment. The keyframe meant for 17.95 s landed at 18.017 s (dropped frames) and opens segment 3. |
| 5a | `05-encode-late-start.fmp4` | 0 | MPEG-TS source whose timestamps begin at 12 s. The container start is the AAC priming frame (11.978667 s), so video starts at 0.0417 s (encode): segment 0, not 2. The server advertises 6 segments for the 30.051 s source, and the last output frame (30.030 s) is the first at or after 30 s, so the list forces it: 6 segments, the last a 1-frame segment 5 with no audio-only fragment after it. |
| 5b | `05-copy-late-start.fmp4` | 0 | Timescale 90000. The same source copied with `-bsf:a aac_adtstoasc`: video starts at 0.0213 s, audio at 0 with no priming frame, keyframes every 2.002 s, 5 segments. |
| 5c | `05-copy-late-start-seek12.fmp4` | 2 | Timescale 90000. Stream-copy replacement attempt of the same source from segment 2 (`-ss 12`). The MPEG-TS demuxer lands on the keyframe at 12.033 s, after the boundary, so nothing is preroll, and its first fragment carries audio from 11.861 s: seven AAC frames before the boundary, all of which segment 1 of 5b holds once each audio sample is placed by its start. Segments 2–4 carry 5b's video samples at the same ticks. Ends with one audio-only fragment. |
| 6 | `06-encode-audio-tail.fmp4` | 0 | 20 s video, 23.5 s audio. The recording ends in 4 audio-only fragments (19.95, 20.95, 21.95, 22.95 s), which join segment 3. No fragment without video precedes the first segment. |
| 7 | `07-copy-start0.fmp4` | 0 | 2.002 s-GOP 23.976 fps copy from the start: 11 segments of 3 keyframes each. |
| 7b | `07-copy-seek30.fmp4` | 5 | Stream-copy replacement attempt at `-ss 30`. The seek lands on the keyframe at 28.028 s (segment 4 < 5). That keyframe fragment and the non-sync fragment after it (2 fragments) are discarded preroll. Segments 5–10 carry exactly 7's video samples at the same ticks. Their audio is the same packets at the same times, but each fragment starts one AAC frame earlier (see findings). |
| 8 | (pairs over 1, 5b, 7, 9, 13, 16) | – | `initializationSegmentIdentityPairs`: `ftyp`+`moov` is byte-identical between the start-0 and seek recordings for libx264 (1346 B), stream copy (1328 B), the MPEG-TS stream copy (1305 B), stream copy with encoded audio (1348 B), SVT-AV1 (1334 B) and libx265 (3848 B). `initializationSegmentDifferencePairs`: the encode and copy initialization segments of the same source differ. Each initialization segment has a zero edit list and zero `trex` defaults. |
| 9 | `09-svtav1-vfr.fmp4` | 0 | The VFR source through SVT-AV1 (145-frame GOP + the list): 1582 frames, exactly one keyframe in every interval, segment starts on exactly the ticks of 3 (libx264). SVT-AV1 honours the forced keyframe. No B-frame reordering, so composition offsets are 0. |
| 9b | `09-svtav1-vfr-seek30.fmp4` | 5 | The same as a replacement attempt from segment 5, seeking one period early (`-ss 24`): starts at 24.024 s, 1007 frames, no padding. Segment 4 (one keyframe) is discarded preroll. Every delivered segment, 5–10, starts on exactly 9's ticks, including segment 5 at 30.030 s, which a seek to 30 s itself missed (see Observations). |
| 10 | `10-copy-gop-exceeds-period.fmp4` | 0 | A copy with a keyframe every 10.01 s. The sync-first fragment at 20.02 s (fragment 20) is segment 3 while 2 is expected. It closes segment 1 (10.01–20.02 s, fragments 10–19), so segments 0 and 1 are delivered, then grouping fails with `SKIPPED_SEGMENT_NUMBER` and fragment 20 onwards is never grouped. |
| 11 | `11-encode-cfr-floored-gop.fmp4` | 0 | The unverified-encoder case: the first 30 s of 1 under the floored GOP of 143 frames. The GOP keyframe one frame before every later boundary (frames 143, 287, 431, 575) and on the last frame (719) is a 1-frame keyframe-first fragment inside the earlier interval, so each of the 5 segments holds two keyframes, and every segment still opens at its forced keyframe. |
| 12 | `12-encode-cfr-missed-forced-keyframe.fmp4` | 0 | libx264, first 30 s, 145-frame GOP, 18 left out of the list. The GOP count restarts at the forced keyframe at frame 288 (12.012 s), so the backstop keyframe comes 145 frames later at frame 433 (18.060 s), inside interval 3: 0@0, 1@6.006, 2@12.012, 3@18.060, 4@24.024, no number skipped. |
| 12b | `12-svtav1-cfr-missed-forced-keyframe.fmp4` | 0 | The same through SVT-AV1: its backstop also lands at frame 433 (18.060 s). |
| 13 | `13-x265-cfr.fmp4` | 0 | Constant 23.976 fps libx265, 66 s, with the worker's arguments (closed GOPs, no scene cuts, sample entry `hvc1`) under the verified GOP of 145 frames. As in 1, the only keyframes are the forced frames (0, 144, 288 … 864, 1007, 1151, 1295, 1439), one per segment, 11 segments on 1's ticks. Every keyframe is an IDR picture (NAL type 20) with no leading pictures. |
| 13b | `13-x265-cfr-missed-forced-keyframe.fmp4` | 0 | libx265 with 18 left out of the list: its GOP count restarts at frame 288 as well, and the backstop comes at frame 433 inside interval 3 as an IDR picture (NAL type 20). So the segment it opens decodes on its own. |
| 13c | `13-x265-cfr-floored-gop.fmp4` | 0 | libx265 with the worker's arguments under the floored GOP of 143 frames of an encoder that is not keyframe-verified, first 30 s. As in 11, the GOP keyframe one frame before every later boundary (frames 143, 287, 431, 575) and on the last frame (719) joins the earlier segment: 5 segments of two keyframes, each opening at its forced keyframe. Every keyframe is an IDR picture (NAL type 20). |
| 13d | `13-x265-cfr-seek30.fmp4` | 5 | The libx265 replacement attempt from segment 5, seeking one period early (`-ss 24`): starts at 24.024 s, and segment 4 is discarded preroll. The list `30,36,…,66` forces the frames 13 forced (720, 864, 1007, 1151, 1295, 1439), so every delivered segment, 5–10, starts on exactly 13's ticks. Every keyframe is an IDR picture. Ends with one audio-only fragment. |
| 14 | `14-copy-audio-ends-early.fmp4` | 0 | 20 s of video, 8 s of audio, copied. The fragment at 8.008 s still carries the last three audio samples, which end at 8.0 s, and from 9.009 s on FFmpeg writes fragments with a video `traf` alone, so no audio sample ever starts at or after the end of segments 1–3; 4 segments, the last from 18.018 s. |
| 15 | `15-copy-adjacent-keyframes.fmp4` | 0 | 22.05 kHz audio, whose 46.4 ms AAC frames outlast the 41.7 ms video frames, starting 20 ms after the video, and a keyframe on every frame from 5.6 s to 6.6 s, copied. FFmpeg cuts a one-frame fragment at each of those keyframes, and some carry no audio. The one at 6.006 s, which opens segment 1, is one of them, and the next fragment carries an audio sample from 5.964 s, inside segment 0. 4 segments. |
| 16 | `16-copy-encode-audio.fmp4` | 0 | 7's source with its video copied and its audio encoded (the worker's audio transcode): 11 segments of 3 keyframes each, every keyframe starting a fragment. |
| 16b | `16-copy-encode-audio-seek30.fmp4` | 5 | Its replacement attempt at `-ss 30`. The seek lands on the keyframe at 28.028 s, but the encoded audio starts at the seek point (29.979 s, its priming frame). `delay_moov` writes nothing until every track has a sample, so the first fragment holds the video from 28.028 s to 30.072 s, with the keyframe at 30.030 s as its 49th sample, and one audio sample. That keyframe opens segment 5 inside the fragment: the 48 samples before it are segment 4's discarded preroll, and segment 5 carries exactly 16's video samples at the same ticks. The next two fragments start with non-sync samples; the next keyframe that starts a fragment is at 32.032 s. |
| 17 | `17-copy-audio-ends-long-before-video.fmp4` | 0 | 180 s of video, 20 s of a sine tone, copied: the audio outlasts FFmpeg's 10 s muxer interleave bound many times over. The fragment at 20.020 s carries the last three audio samples, which end at 20.0 s, and fragments 21–179 carry video alone, so no audio sample ever starts at or after the end of segments 3–28; 30 segments, the last from 174.174 s. |
| 17b | `17-encode-audio-ends-long-before-video.fmp4` | 0 | The same source encoded under the recipe: the fragment at 20.103 s carries the last audio sample, at 19.947 s, and fragments 21–179 carry video alone; 30 segments of one forced keyframe each. |

Box facts every recording shares (useful for the reader):
- Top-level boxes are only `ftyp`, `moov`, `moof` and `mdat`, in that order, with no `largesize`, `styp` or `sidx`.
- `moov` holds `mvhd`, two `trak`s (video track 1, audio track 2), `mvex` and `udta`. The video sample entry is `avc1` (libx264 and copies), `av01` (SVT-AV1) or `hvc1` (libx265).
- Every `traf` has `tfhd` flags `0x02003a` (default-base-is-moof plus default duration, size and flags), `tfdt` version 1 and exactly one `trun`.
- Every `trun` is version 1 (signed composition offsets) with a data offset. Sync status is carried by `trun` first_sample_flags (0x4) when present, otherwise by the `tfhd` default flags. Per-sample flags (0x400) never occur.
- The largest fragment is 3.4 KB; every keyframe starts a fragment, except the keyframe at 30.030 s inside 16b's first fragment.
- In every recording from the start except 5b the first audio `tfdt` is 2^64 − 1024: the unsigned wrap of −1024, the AAC priming. 5b copies the ADTS audio of an MPEG-TS source, which carries no priming frame, so its audio starts at 0.

## Verified encoders

ADR 0037, as amended, gives an encoder verified by recording to honour time-based forced keyframes,
and to restart its GOP count at each one, a frame-count GOP of ceil(P × fps) + 1 (145 at 23.976 fps).
While forcing works that GOP never fires; if a forced keyframe were ever dropped, the backstop lands
just past that boundary, inside its interval. An encoder not verified keeps floor(P × fps) (143),
which places a second keyframe one frame before most boundaries: before every later boundary of 11
and 13c, and before 9 of 10 in the earlier 66 s floored recording of 1.

Every verdict below rests on recordings that force keyframes from the list. Where a recording's
first frame is at zero (1, 6, 11, 12 and 12b) the list forces the same frames as the earlier
expression `expr:gte(t,n_forced*6)` did, and those recordings are byte-identical to the expression's.
Where the first frame is after zero (3, 5a, 9) or the attempt seeks (1b, 9b, 13d), the list forces
the first frame at or after each boundary on the zero-based timeline, and every keyframe-verified
encoder keys exactly those frames.

| Encoder | Honours the forced keyframe | Restarts its GOP count there | Keyframe picture | Verdict |
|---|---|---|---|---|
| libx264 | yes: 1, 1b, 3, 5a, 6 key only the forced frames, one per segment | yes: 12's backstop at frame 433 = 288 + 145 | an IDR (NAL type 5) at every keyframe of every H.264 recording, 12's backstop included | verified: 145 |
| SVT-AV1 | yes: 9, 9b, on the frames libx264 chose in 3 | yes: 12b's backstop at frame 433 | a shown key frame at every keyframe of 9, 9b and 12b, 12b's backstop included | verified: 145 |
| libx265, closed GOPs | yes: 13 and 13d key only the forced frames, one per segment | yes: 13b's backstop at frame 433 | an IDR (NAL type 20) at every keyframe of 13, 13b, 13c and 13d, 13b's backstop included | verified: 145 |
| hardware encoders | not recorded | not recorded | not recorded | not verified: 143 until worker #14 |

## Anchoring on the zero-based timeline

The list's times are absolute, so every attempt forces the same frame at a boundary it shares with
another attempt, whatever its seek:
- Every segment 1b and 9b deliver, 5–10, starts on exactly the ticks of 1's and 9's
  (`RecordedFfmpegOutputTest`), and the producer discards their preroll, segment 4, and delivers
  segment 5 from 30.030 s (`ProducerTest`).
- The first boundary also needs the early seek. An earlier recording of 9b that sought to 30 s itself
  started at 30.0717 s, the first source frame after 30 s, and so lacked the frame 9 holds at
  30.030 s, a repeat of the source frame at 29.988 s. Seeking to 24 s starts on the frame at
  24.024 s, and from 30 s on 9b's frames sit on 9's ticks. A verified encoder's preroll holds only its
  own first keyframe (1b and 9b), because the list starts at segment 5 and the GOP outlasts a period.
- 3, 5a and 9, whose first frame is at 41.7 ms, open every segment on the first frame at or after its
  boundary, the same ticks as 1.

The list is one argument, and Linux passes an argument of at most 131,071 bytes before its
terminating NUL (32 pages of 4 KiB). `FfmpegCommandBuilder` ends the list at the last time that fits:
21,370 boundaries at a 6 s period from zero (35.6 h), 22,771 at 2 s (12.6 h). Past it only the GOP
places keyframes. A GOP of ceil(P × fps) + 1 frames is longer than a period, so its keyframes drift
later each time and eventually skip a whole interval. When the list from the variant's first segment
ends before the last advertised boundary, every encoder therefore takes floor(P × fps),
keyframe-verified or not. Every attempt of the variant takes that GOP, even one whose own list fits:
libx265 writes its settings into the initialization segment, and 13 and 13c, keyint=145 and
keyint=143, differ from byte 1,205 of their 3,848-byte initialization segments. Those settings are an SEI
message in the record, which the producer removes, so the worker delivers the same 1,421-byte
initialization segment for both.

## The HLS muxer as differential evidence

For every fixture the same source was also run through the HLS muxer (`-f hls -hls_segment_type fmp4`):
- `*.hls-recipe`: the HLS muxer recipe that ADR 0037 replaces, as `FfmpegCommandBuilder` built it (no `-start_at_zero`, `-max_delay`, its keyframe arguments, with audio; its copies take the pipe recipe's video arguments). It forces keyframes with the expression `expr:gte(t,n_forced*6)`, whose `t` counts from the attempt's first frame.
- `*.video-only`: the pipe recipe's own flags and keyframe arguments (the same list), video only; for
  fixtures 11, 12, 12b, 13b and 13c over the same first 30 s of the source.
- `*.pipe-keyframes-with-audio` (1, 5a, 9): the pipe recipe's own flags and keyframe arguments, with audio.

Each HLS segment's first video sample is mapped to the pipe recording by its ordinal in decode order.
Where the two runs share their video arguments (every stream copy, every `video-only` run and every
`pipe-keyframes-with-audio` run), `frameIdentity.identicalPackets` shows every video packet identical
in size and SHA-256, so the ordinal names the same frame, and the recorder fails when one differs.
The HLS recipe's own encodes use other keyframe arguments, so their packets differ from the first
GOP difference on: for them only the packet count is checked, and the mapping rests on both encoders
receiving the same constant-rate frames in the same order. That is not checked frame by frame.
The HLS files' own timestamps are not usable directly. With `frag_discont`, movenc "pretends the stream started at pts=0" (movenc.c 7091) and snaps
each later fragment's dts to the running duration sum. So HLS segment timestamps lose the start
offset (5a, 5b) and drift by a frame on the VFR B-frame copy (4).

Agreement, exact in ticks:

| Fixture | hls-recipe | video-only | Note |
|---|---|---|---|
| 1, 2, 6, 7 | agrees | agrees | 1 also agrees with its pipe-keyframes-with-audio run |
| 1b | – | differs on 7–10 | agrees with the start-0 recording's HLS output on 5–10 (reference 2) |
| 3 | differs on 1–10 | differs on 1–10 | reference 1 |
| 5a | differs on 1–5 | – | its pipe-keyframes-with-audio run differs on 1–5 (reference 1); a video-only run starts its video one frame earlier (see Observations) |
| 5b | agrees (with `aac_adtstoasc` added; the HLS recipe's exact copy exits 255) | agrees | |
| 5c | agrees with the start-0 recording's HLS output on 2–4 | – | |
| 7b | agrees with the start-0 recording's HLS output | – | its own HLS run is misaligned: 28.028, 34.034, 40.040 … as ADR 0037 describes |
| 9 | differs on 1–10 | differs on 1–10 | hls-recipe is a different encode (4 below); video-only as reference 1; its pipe-keyframes-with-audio run agrees (reference 3) |
| 9b | – | differs on 7–10 | agrees with 9's pipe-keyframes-with-audio run on 5–10 |
| 13d | – | differs on 7–10 | agrees with 13's video-only run on 5–10 |
| 4 | differs on segment 3 | differs on segment 3 | reference 1 below |
| 10 | differs on segment 2 | – | the HLS muxer numbers sequentially and never skips |
| 11, 12, 12b, 13, 13b, 13c | – | agrees | the first video frame is at zero, so hlsenc measures from the grid's zero |
| 14 | agrees | agrees | |
| 15 | – | agrees | |
| 16, 16b | – | – | no HLS run: the video packets and cut points are 7's |
| 17, 17b | – | – | no HLS run: they record when audio stops arriving, not where the muxer cuts |

The list anchors the pipe recipe's keyframes on the zero-based timeline, while hlsenc and the HLS
recipe's expression both measure from the attempt's first frame. Before the list, the pipe recipe forced
keyframes with the same expression, and the encodes that start after zero (3, 5a, 9) and the encoded
seeks (1b, 9b) agreed with their own HLS runs; they now disagree for the reasons below. The HLS
recipe's own runs of a replacement attempt seek to 30 s and hold a period fewer frames than 1b and 9b,
so the ordinal mapping cannot compare them; the start-0 runs stand in for them.

Every disagreement is explained by where hlsenc measures from, or by an HLS run that encodes other
keyframes, not by the grouping. `hlsencModel`
re-implements hlsenc.c's cut rule (FFmpeg n8.1 source, lines 2440–2489):
- the first packet opens segment 0;
- a later keyframe cuts when `pts − start_pts ≥ hls_time × number` in the video time base.

It applies that rule to the HLS run's own keyframes, with `start_pts` = the pts of the first packet
the muxer receives. The model reproduces the observed HLS cut list in all 35 HLS comparisons
(`reproducesHlsCuts: true`). Its reference differs from the grid's zero in three ways:

1. **First video frame after zero** (3, 4, 5a, 9: video starts 41 or 41.7 ms after the container
   start). hlsenc cuts at the first keyframe at or after N·P + 41.7 ms. The list forces the first
   frame at or after N·P, which lies less than one frame past it (6.006, 12.012 … s), so hlsenc can
   never cut there: it cuts at the next forced keyframe, one period later, and from segment 1 on its
   numbers run one behind the grid's (the video-only runs of 3 and 9, 5a's pipe-keyframes-with-audio
   run). The HLS recipe's expression measures `t` from the same first frame as hlsenc, so its forced
   keyframes sit one frame after the list's (6.048 against 6.006 s) and hlsenc cuts at each of them,
   one frame after the grid (3's and 5a's hls-recipe runs). In 4, the source keyframe at 18.017 s lies
   inside [18, 18.041), so hlsenc cuts one keyframe later (19.018 s).
2. **Seek recordings** (1b, 9b, 13d, 7b). hlsenc measures from the seek's first frame (24.024 s for
   the encodes, 28.028 s for 7b), not from the grid. The lists of 1b, 9b and 13d force the frames the
   start-0 recordings forced, so they agree with the start-0 HLS output on 5–10. Their own video-only runs
   (`-start_number 4`, so that the preroll keeps its number) cut at 30.030 s and 36.036 s, 6.006 s and
   12.012 s after 24.024 s, but not at 42.0003 s, 17.976 s after it, so they run one behind from
   segment 7. For 7b this is exactly the misalignment ADR 0037 describes for a replacement attempt.
3. **An audio reference read in the video time base.** When the first audio dts precedes the first
   video dts, `start_pts` is the audio packet's pts in audio ticks, yet hlsenc subtracts it in the
   video time base. It replaces it only if a video packet's raw pts is smaller (hlsenc.c 2450–2458).
   - With 48 kHz audio and libx264 B-frames, video comes first (dts −83 ms against −21 ms).
   - With SVT-AV1 (dts = pts), audio comes first: the reference is −1024 read at 1/24000 = −42.7 ms.
     That reference lies before zero, so hlsenc cuts at every forced keyframe of the list, and 9's
     pipe-keyframes-with-audio run agrees with the grid.
   - In an earlier 8 kHz-audio recording (not kept), audio came first for libx264 too. hlsenc then cut
     at the GOP keyframe one frame before each boundary, and after a seek it cut at every keyframe.

   This also affects the HLS recipe's fMP4 variants (HEVC/AV1) in production. In MPEG-TS HLS every stream
   shares 1/90000, so the misread should cancel there (not tested).
4. **A different encode** (9's hls-recipe run). The HLS recipe gives SVT-AV1 a 144-frame GOP and no
   forced keyframe, so its keyframes sit 144 frames apart from the first frame: one frame after the
   list's at segment 1 (6.048 against 6.006 s), drifting 6 ms per segment from there (42.084 against
   42.0003 s at segment 7). hlsenc cuts at its own keyframes, as the model predicts.

## ADR 0037 claims checked on the pinned FFmpeg

Reproduced:
- Initialization segment:
  - `ftyp`+`moov` is byte-identical across start 0 and a seek, for encode and for stream copy (and SVT-AV1).
  - It has a zero edit list and zero `trex` defaults.
  - Encode and copy initialization segments differ.
- Fragmentation:
  - With no `min_frag_duration` and `-frag_duration` 1 s, every keyframe starts a fragment, except
    in the first fragment: `delay_moov` writes nothing until every track has a sample, so when a
    stream copy's replacement attempt encodes its audio, which starts at the seek point, the first
    fragment runs from the keyframe the seek landed on past the next one (16b).
  - Every fragment that does not start at a keyframe starts with a non-sync sample.
- Timestamps and grouping:
  - The `cmaf` flag writes negative composition offsets.
  - The first audio `tfdt` is the unsigned wrap of −1024.
  - A fragment's start plus its sample durations misses the next fragment's start by up to ±5005 ticks
    (209 ms). A fragment's end cannot be computed from its durations.
  - Fragments without video appear only after the last video sample: one per target of audio that
    outlasts the video (4 for 3.5 s). None ever precedes the first segment.
  - On a 10 s-GOP copy the keyframes fall in segments 0, 1, 3; the first skip is at 20.02 s.
- Start point and seeking:
  - `-start_at_zero` on an MPEG-TS source puts zero at the AAC priming frame. Video starts at
    0.021 s (copy) and 0.042 s (encode), exactly the ADR's numbers.
  - An encoded seek starts at the seek point: `-ss 24` starts 1b and 9b at 24.024 s. A stream-copy
    seek to 30 s starts at 28.028 s, which is preroll.
  - The HLS recipe starts a stream-copy replacement attempt's segments at 28.028, 34.034 and 40.040 s.
- Frame rate:
  - `-r` with no `-fps_mode` yields the same constant-rate frames as the HLS recipe on a VFR
    source (1582 = 1582).
  - After a seek it pads nothing. An explicit `-fps_mode cfr` after `-ss 24` pads from zero
    (1583 frames starting at 0, against 1007 starting at 24.024 s).
- SVT-AV1:
  - It honours the time-based forced keyframe and places keyframes on exactly the frames libx264
    chose (9 = 3 tick for tick).
  - It has a keyframe in every interval after a seek, with no padding.
- Grouping against the HLS muxer:
  - Under the verified GOP, grouping reproduces the HLS muxer's cut points exactly for the constant
    23.976 fps encode (1) and the stream copy (7), and an encoded replacement attempt (1b)
    reproduces the start-0 recording's HLS cut points on every segment it delivers.
- Audio and muxer flags:
  - An MPEG-TS AAC copy without `-bsf:a aac_adtstoasc` exits 255 with "Malformed AAC bitstream
    detected".
  - The filter leaves an MP4-source copy byte-identical.
  - `-max_delay 5000000` leaves mp4 output byte-identical.

Did not reproduce exactly (mechanism in each case; none is a grouping error):
- **"… reproduced the HLS muxer's cut points … 11 into 11 for the variable-frame-rate encode" and
  "the two rules agree … at 23.976 fps for encode, stream copy and a seek"** held while the pipe
  recipe forced keyframes with the expression, which measures from the attempt's first frame as hlsenc
  does. Under the list the variable-frame-rate encode (3) still delivers 11 segments, one keyframe
  each, but on the first frame at or after each boundary, where hlsenc, measuring from 41.7 ms,
  cannot cut; an encoded seek likewise disagrees with its own HLS run (references 1 and 2).
- **"… the encode restart starts at 30.030 s and needs no discard."** That describes a seek to the
  boundary itself. The fixtures' encoded replacement attempts seek one period earlier and discard that
  period, because the seek to the boundary dropped 9's frame at 30.030 s (see [Anchoring](#anchoring-on-the-zero-based-timeline)).
- **"… including the one-frame-early 42.000 s."** The list forces the first frame at or after 42 s,
  42.0003 s (frame 1007), on every encode whose frames lie on the 23.976 grid from zero: 1, 1b, 3, 9
  and 9b. The expression had forced 42.042 s on the VFR source, whose first frame is at 41.7 ms.
- **The ADR's replacement-attempt numbers (28.028, 34.034, 40.040 s)** reproduce only because the fixtures
  use 48 kHz audio. With 8 kHz audio, hlsenc's audio reference made it cut at every keyframe
  (reference 3).
- Not measured here: the 1000-segment recording per software encoder, `min_frag_duration` behaviour, MPEG-TS
  input-seek behaviour, MKV sources, the demuxer's 2002-tick presentation delay, and the fragment spans
  (1.001 s / 1.033 s).

## Observations for the producer and the server

These are facts about the recordings, not decisions. Worker #39's producer and an ADR 0037 follow-up
settle what, if anything, changes because of them.

- **The floored GOP adds an IDR one frame before most boundaries** (11). At 23.976 fps the forced
  keyframes are 143 or 144 frames apart. Whenever the gap is 144, the 143-frame GOP inserts an IDR
  one frame earlier. Grouping handles it (the 1-frame keyframe-first fragment joins the earlier
  segment when it falls before N·P), at the cost of an extra IDR and a tiny fragment per segment.
  The verified GOP (145) removes it for libx264, SVT-AV1 and libx265.
- **An encoded replacement attempt forces the first attempt's frame at every later boundary** (1b
  against 1, 9b against 9, 13d against 13). The list compares each frame's timestamp with absolute media times, so 1b
  forces frames 1007, 1151, 1295 and 1439 exactly as 1 does. Under the expression, which measured `t`
  from the attempt's first frame (30.030 s), 1b forced 1008, 1152, 1296 and 1440, and a playlist that
  joined the first attempt's segment 6 to the replacement attempt's segment 7 skipped frame 1007.
- **An encoded replacement attempt that seeks to its first boundary can miss the first attempt's
  first frame** (9b). 9 fills 30.030 s with a repeat of the source frame at 29.988 s, which an
  accurate seek to 30 s discards, so that seek started 9b's segment 5 at 30.0717 s, and a playlist
  joining 9's segment 4 to it skipped the repeated frame. Seeking one period earlier removes it: 9b
  now delivers segment 5 from 30.030 s. An MPEG-TS source, whose demuxer lands on the next keyframe
  after the target, has no encoded replacement attempt recording here.
- **libx265 keys every keyframe as an IDR picture once its GOPs are closed** (13, 13b, 13c, 13d). Under
  its default open GOP, libx265 keyed every forced keyframe after frame 0 as a CRA picture, although
  the worker passes `-forced-idr 1`, and 13b's backstop CRA led a RASL picture that references the
  GOP before it. The worker now passes `-x265-params no-scenecut=1:no-open-gop=1`, which closes the
  GOPs and stops keyframes at scene cuts.
- **An encode can end in a 1-frame segment.** The server advertises 6 segments for 5a's 30.051 s
  source, and 5a's last constant-rate frame, at 30.030 s, is the first at or after the last advertised
  boundary, 30 s. The list forces it, so it forms segment 5 holding one frame and nothing else. Under
  the expression 5a ended with segment 4 and never delivered the advertised segment 5.
- **An MPEG-TS encode's video starts one frame earlier without its audio** (5a). With the audio
  mapped, the constant-rate output's first frame is at 41.7 ms; with the video alone it is at 0, so
  the list forces the frames one later (144, 288 … instead of 143, 287 …). Every attempt of a
  variant maps the same streams, so its attempts agree; 5a's HLS comparison with the pipe's keyframes
  therefore keeps the audio.
- **A stream-copy replacement attempt's fragments start their audio before the boundary** (7b, 5c).
  7b's fragments carry the same audio packets at the same times as 7's, but each starts one packet
  (1024 ticks, 21.3 ms) earlier. 5c's first fragment, from an MPEG-TS source, starts its audio at
  11.861 s, seven packets before the boundary. Grouped by fragment, 5b's segment 1 ends its audio at
  574464 (11.968 s), so the replacement attempt's first segment would repeat five packets. The producer instead places
  each audio sample in the segment whose interval holds its start, so the replacement attempt's
  first segment starts its audio exactly where the start-0 attempt's segment before it ends
  (`ProducerTest`). Video is identical.
- **A keyframe fragment past a boundary can carry no audio while audio before it is still to come**
  (15). The fragment at 6.006 s opens segment 1 without audio, and the next fragment carries an audio
  sample from 5.964 s, inside segment 0. The producer therefore closes a segment only once an audio
  sample of a later segment has arrived, or the output ends.
- **Audio can end before the video** (14). The fragment at 8.008 s carries the last three audio
  samples, which end at 8.0 s, and from 9.009 s on FFmpeg writes fragments with a video `traf` alone.
  Nothing in the output says that the audio has ended, and such a fragment does not show that the
  audio before its boundary has all arrived (15), so segments 1–3 wait until the output ends. Only a
  movie without a sound track closes each segment at the keyframe that opens the next.
- **Trailing audio-only fragments are common, not exceptional.** With 48 kHz audio they end 1, 1b, 2,
  3, 4, 5b, 5c, 10, 12, 13, 13b and 13d, as well as 6.
- `hlsenc` measures fMP4 cut points in the video time base from a reference that can be an audio
  packet's pts. That is a latent defect of the HLS recipe's fMP4 path (reference 3 above).
