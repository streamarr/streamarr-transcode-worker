# Keyframe-position fixtures

Small sources that pin how the worker reads keyframe positions from a container index: the MP4 and
MOV sample tables, and the Matroska Cues. `../../keyframe-fixtures/record-fixtures.sh` makes every
file with the FFmpeg in the pinned worker image. Each source is 4 s of 64x36 `testsrc2` video at
24 fps with a 440 Hz tone, from FFmpeg's lavfi test sources. The video has keyframes at frames 0,
17, 50 and 70. No file comes from a media library.

| File | What it is | What the worker reports |
|---|---|---|
| `mp4-bframes-editlist.mp4` | H.264 with B-frames, so the MP4 has composition offsets and an edit list | Positions from the sync-sample table |
| `mkv-libavformat.mkv` | Matroska from libavformat, with Cues after the Clusters | Positions from the Cues |
| `mp4-fragmented.mp4` | Fragmented MP4 (`frag_keyframe+empty_moov`) | A packet scan: no container index |
| `mkv-pipe.mkv` | Matroska written to a pipe, which has no Cues | A packet scan: no container index |
| `mpegts.ts` | MPEG-TS | A packet scan: no container index |
| `avi.avi` | AVI without B-frames | A packet scan: no container index |
| `mp4-truncated.mp4` | `faststart` MP4 cut just before the last keyframe's packet | A packet scan: unreadable last keyframe |
| `mkv-truncated-cues-front.mkv` | Matroska with Cues at the front, cut just before the last keyframe's packet | A packet scan: unreadable last keyframe |
| `mp4-h264-aac-ac3.mp4` | H.264 with B-frames, AAC and AC-3 (a 660 Hz tone), each with the edit list FFmpeg writes for its delay or priming | Positions and a measurement from the sample tables |
| `mov-hevc-aac.mov` | HEVC with B-frames and AAC in MOV | Positions and a measurement from the sample tables |
| `ffmpeg-version.txt` | The first line of `ffmpeg -version` from the recording image | |

`scans/` keeps no source. For each recording it holds the probe's own ffprobe output, without the
source's path (`.json`), the framecrc lines of the probe's packet scan of the first stream
(`.framecrc`), and FFmpeg's diagnostics when it printed any (`.diagnostics`), without the
addresses that FFmpeg prints with them. FFmpeg's raw H.264 and HEVC demuxers give no packet a
presentation time, and its AVI demuxer gives none to a stream with B-frames.

| Recording | What the source is | What the worker reports |
|---|---|---|
| `avi.avi` | The `avi.avi` fixture | Positions from a packet scan |
| `avi-bframes.avi` | The same video with B-frames, in AVI, without audio | Keyframes unavailable: a keyframe without a time |
| `h264-sei-1.h264` | FFmpeg's FATE sample `h264/sei-1.h264`: raw H.264 with B-frames | Keyframes unavailable: a keyframe without a time |
| `h264-ref_10.avi` | FFmpeg's FATE sample `h264/ref_10.avi`: H.264 with B-frames in AVI | Keyframes unavailable: a keyframe without a time |
| `hevc-food.hevc` | FFmpeg's FATE sample `hevc/food.hevc`: raw HEVC with B-frames | Keyframes unavailable: a keyframe without a time |
| `hevc-pir.hevc` | FFmpeg's FATE sample `hevc/pir.hevc`: raw HEVC without B-frames | Positions from a packet scan |

`measurements/` holds, for each measured source, the probe's own ffprobe output without the source's
path (`.json`), and the streams and packets that ffprobe lists for it (`.packets.json`). A stream's
measured bytes must equal the sum of its listed packet sizes, and its bytes in each keyframe interval
the sum of the sizes of the packets in that interval.

`FfprobeExecutorKeyframeTest` reads these files with a scripted ffprobe, and answers its packet
scans with the recordings in `scans/`. `WorkerMediaSmokeTest`
reads them with the real ffprobe. It compares the positions with the keyframe packets of
`ffmpeg -copyts -start_at_zero`. It also stream-copies `mp4-bframes-editlist.mp4` into more MP4
edit lists and into negative composition offsets at test time.

## Regenerating

From the repository root, with Docker:

```
src/test/keyframe-fixtures/record-fixtures.sh
WORKER_IMAGE=streamarr-worker:local src/test/keyframe-fixtures/record-fixtures.sh
FATE_SUITE=/path/to/fate-suite src/test/keyframe-fixtures/record-fixtures.sh
```

Without `FATE_SUITE`, the script keeps the recordings of the FATE samples as they are.

The script overwrites the files in this directory, so `git diff` shows what a new recording
changed. Re-record after an FFmpeg lock update, then run the keyframe tests and the smoke test.
