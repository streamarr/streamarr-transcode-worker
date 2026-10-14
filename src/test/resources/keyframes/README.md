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
| `ffmpeg-version.txt` | The first line of `ffmpeg -version` from the recording image | |

`FfprobeExecutorKeyframeTest` reads these files with a scripted ffprobe. `WorkerMediaSmokeTest`
reads them with the real ffprobe. It compares the positions with the keyframe packets of
`ffmpeg -copyts -start_at_zero`. It also stream-copies `mp4-bframes-editlist.mp4` into more MP4
edit lists and into negative composition offsets at test time.

## Regenerating

From the repository root, with Docker:

```
src/test/keyframe-fixtures/record-fixtures.sh
WORKER_IMAGE=streamarr-worker:local src/test/keyframe-fixtures/record-fixtures.sh
```

The script overwrites the files in this directory, so `git diff` shows what a new recording
changed. Re-record after an FFmpeg lock update, then run the keyframe tests and the smoke test.
